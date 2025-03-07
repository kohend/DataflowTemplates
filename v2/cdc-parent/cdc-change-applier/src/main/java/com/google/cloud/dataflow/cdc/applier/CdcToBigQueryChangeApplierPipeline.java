/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.cdc.applier;


import com.google.cloud.dataflow.cdc.applier.CdcPCollectionsFetchers.CdcPCollectionFetcher;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CdcToBigQueryChangeApplierPipeline} consumes change data corresponding to changes in a
 * database. This data is consumed from a group of Pubsub topics (one topic for each table in the
 * external database); then it is processed and inserted to BigQuery.
 *
 * <p>For each table in the external database, the {@link CdcToBigQueryChangeApplierPipeline} will
 * produce two BigQuery tables:
 *
 * <p>1) One changelog table, with the full sequence of changes made to the table in the external
 * database. This table is also referred to as Staging Table, Changelog table 2) One <b>replica</b>
 * table, which is a replica of the table in the external database. This replica table is built
 * periodically by issuing MERGE statements to BigQuery that synchronize the tables using the
 * changelog table. This table is referred to as the Replica Table.
 *
 * <p>The change data is intended to be generated by a Debezium-based connector, which watches the
 * changelog from the external database, formats the data into Beam {@link Row} format, updates Data
 * Catalog with schema information for each table, and pushes the change data to PubSub.
 */
public class CdcToBigQueryChangeApplierPipeline {

  public static final Integer SECONDS_PER_DAY = 24 * 60 * 60;
  public static final Integer MAX_BQ_MERGES_PER_TABLE_PER_DAY = 1000;

  public static final Long MINIMUM_UPDATE_FREQUENCY_SECONDS =
      Math.round((SECONDS_PER_DAY / MAX_BQ_MERGES_PER_TABLE_PER_DAY) * 1.10);

  private static final Logger LOG =
      LoggerFactory.getLogger(CdcToBigQueryChangeApplierPipeline.class);

  /**
   * The {@link CdcApplierOptions} class provides the custom execution options passed by the
   * executor at the command-line.
   */
  public interface CdcApplierOptions extends PipelineOptions {

    @Description("Comma-separated list of PubSub topics to where CDC data is being pushed.")
    String getInputTopics();

    void setInputTopics(String topic);

    @Description("Comma-separated list of PubSub subscriptions where CDC data is available.")
    String getInputSubscriptions();

    void setInputSubscriptions(String subscriptions);

    @Description("The BigQuery dataset where Staging / Change Log tables are to be kept.")
    String getChangeLogDataset();

    void setChangeLogDataset(String dataset);

    @Description("The BigQuery dataset where the Replica tables are to be kept.")
    String getReplicaDataset();

    void setReplicaDataset(String dataset);

    @Description("How often the pipeline will issue updates to the BigQuery replica table.")
    Integer getUpdateFrequencySecs();

    void setUpdateFrequencySecs(Integer frequency);

    @Description(
        "Whether change updates should come from a single PubSub topic. If this option "
            + "is set to true, then a single input subscription or topic will be expected.")
    @Default.Boolean(false)
    Boolean getUseSingleTopic();

    void setUseSingleTopic(Boolean useSingleTopic);
  }

  private static PDone buildIngestionPipeline(
      String transformPrefix, CdcApplierOptions options, PCollection<Row> input) {
    return input.apply(
        String.format("%s/ApplyChangesToBigQuery", transformPrefix),
        BigQueryChangeApplier.of(
            options.getChangeLogDataset(),
            options.getReplicaDataset(),
            options.getUpdateFrequencySecs(),
            options.as(GcpOptions.class).getProject()));
  }

  /**
   * Main entry point for pipeline execution.
   *
   * @param args Command line arguments to the pipeline.
   */
  public static void main(String[] args) throws IOException {
    CdcApplierOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(CdcApplierOptions.class);

    run(options);
  }

  /**
   * Runs the pipeline with the supplied options.
   *
   * @param options The execution parameters to the pipeline.
   * @return The result of the pipeline execution.
   */
  private static PipelineResult run(CdcApplierOptions options) {

    if (options.getInputTopics() != null && options.getInputSubscriptions() != null) {
      throw new IllegalArgumentException(
          "Either an input topic or a subscription must be provided");
    }

    if (options.getUpdateFrequencySecs() < MINIMUM_UPDATE_FREQUENCY_SECONDS) {
      throw new IllegalArgumentException(
          "BigQuery supports at most 1,000 MERGE statements per table per day. "
              + "Please select updateFrequencySecs of 100 or more to fit this limit");
    }

    Pipeline p = Pipeline.create(options);

    CdcPCollectionFetcher pcollectionFetcher = CdcPCollectionsFetchers.create(options);

    Map<String, PCollection<Row>> pcollections = pcollectionFetcher.changelogPcollections(p);

    for (Map.Entry<String, PCollection<Row>> tableEntry : pcollections.entrySet()) {
      String branchName = tableEntry.getKey();
      PCollection<Row> singularTableChangelog = tableEntry.getValue();
      buildIngestionPipeline(branchName, options, singularTableChangelog);
    }

    // Add label to track Dataflow CDC jobs launched.
    Map<String, String> dataflowCdcLabels = new HashMap<>();
    if (p.getOptions().as(DataflowPipelineOptions.class).getLabels() != null) {
      dataflowCdcLabels.putAll(p.getOptions().as(DataflowPipelineOptions.class).getLabels());
    }
    dataflowCdcLabels.put("dataflow-cdc", "debezium-template");
    dataflowCdcLabels.put("goog-dataflow-provided-template-name", "dataflow_dbz_cdc");
    dataflowCdcLabels.put("goog-dataflow-provided-template-type", "flex");
    p.getOptions().as(DataflowPipelineOptions.class).setLabels(dataflowCdcLabels);

    PipelineResult result = p.run();
    return result;
  }
}
