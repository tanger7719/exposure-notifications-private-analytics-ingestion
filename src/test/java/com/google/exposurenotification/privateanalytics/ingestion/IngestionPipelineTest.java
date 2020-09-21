/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.exposurenotification.privateanalytics.ingestion;

import com.google.exposurenotification.privateanalytics.ingestion.IngestionPipeline.DateFilterFn;
import com.google.exposurenotification.privateanalytics.ingestion.IngestionPipeline.SerializeDataShareFn;
import org.abetterinternet.prio.v1.PrioDataSharePacket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.nio.ByteBuffer;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.ValidatesRunner;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link IngestionPipeline}.
 */
@RunWith(JUnit4.class)
public class IngestionPipelineTest {

  @Rule
  public TestPipeline pipeline = TestPipeline.create();

  @Test
  @Category(ValidatesRunner.class)
  public void testDateFilter() {
    List<DataShare> dataShares = Arrays.asList(
        DataShare.builder().setPath("id1").setCreated(1L).build(),
        DataShare.builder().setPath("id2").setCreated(2L).build(),
        DataShare.builder().setPath("id3").setCreated(3L).build(),
        DataShare.builder().setPath("missing").build()
    );
    PCollection<DataShare> input = pipeline.apply(Create.of(dataShares));

    PCollection<DataShare> output =
        input.apply(
            ParDo.of(new DateFilterFn(StaticValueProvider.of(2L), StaticValueProvider.of(1L))));

    PAssert.that(output).containsInAnyOrder(
        Collections.singletonList(
            DataShare.builder().setPath("id2").setCreated(2L)
                .build()));
    pipeline.run().waitUntilFinish();
  }

  @Test
  @Category(ValidatesRunner.class)
  public void testSerializeDataShare() {
    List<DataShare> dataShares = Arrays.asList(
        DataShare.builder().setPath("id1").setCreated(1L).setRPit(12345L).setUuid("SuperUniqueId")
            .build(),
        DataShare.builder().setPath("id2").setCreated(2L).setRPit(123456L).setUuid("NotSoUniqueId")
            .build()
    );

    List<PrioDataSharePacket> avroDataShares = Arrays.asList(
        PrioDataSharePacket.newBuilder().setEncryptionKeyId("hardCodedID")
            .setRPit(12345L)
            .setUuid("SuperUniqueId")
            // Current hard-coded value of encrypted payload in pipeline is {0x01, 0x02, 0x03, 0x04, 0x05}.
            .setEncryptedPayload(ByteBuffer.wrap(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))
            .build(),
        PrioDataSharePacket.newBuilder().setEncryptionKeyId("hardCodedID")
            .setRPit(123456L)
            .setUuid("NotSoUniqueId")
            .setEncryptedPayload(ByteBuffer.wrap(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))
            .build()
    );
    PCollection<DataShare> input = pipeline.apply(Create.of(dataShares));

    PCollection<PrioDataSharePacket> output =
        input.apply("SerializeDataShares", MapElements.via(new SerializeDataShareFn()));

    PAssert.that(output).containsInAnyOrder(avroDataShares.get(0), avroDataShares.get(1));
    pipeline.run().waitUntilFinish();
  }

  @Test
  @Category(ValidatesRunner.class)
  public void processDataShares_valid() {
    IngestionPipelineOptions options = TestPipeline
        .testingPipelineOptions().as(IngestionPipelineOptions.class);
    options.setStartTime(StaticValueProvider.of(2L));
    options.setDuration(StaticValueProvider.of(1L));
    options.setMinimumParticipantCount(StaticValueProvider.of(1L));
    List<DataShare> inputData = Arrays.asList(
        DataShare.builder().setPath("id1").setCreated(1L).build(),
        DataShare.builder().setPath("id2").setCreated(2L).build(),
        DataShare.builder().setPath("id3").setCreated(4L).build(),
        DataShare.builder().setPath("missing").build()
    );
    List<DataShare> expectedOutput =
        Arrays.asList(DataShare.builder().setPath("id2").setCreated(2L).build());

    PCollection<DataShare> actualOutput = IngestionPipeline
        .processDataShares(pipeline.apply(Create.of(inputData)), options);

    PAssert.that(actualOutput).containsInAnyOrder(expectedOutput);
    pipeline.run().waitUntilFinish();
  }

  @Test(expected = AssertionError.class)
  @Category(ValidatesRunner.class)
  public void processDataShares_participantCountlessThanMinCount() {
    IngestionPipelineOptions options = TestPipeline
        .testingPipelineOptions().as(IngestionPipelineOptions.class);
    options.setStartTime(StaticValueProvider.of(2L));
    options.setDuration(StaticValueProvider.of(1L));
    options.setMinimumParticipantCount(StaticValueProvider.of(2L));
    List<DataShare> inputData = Arrays.asList(
        DataShare.builder().setPath("id1").setCreated(1L).build(),
        DataShare.builder().setPath("id2").setCreated(2L).build(),
        DataShare.builder().setPath("id3").setCreated(4L).build(),
        DataShare.builder().setPath("missing").build()
    );

    IngestionPipeline
        .processDataShares(pipeline.apply(Create.of(inputData)), options);
    pipeline.run().waitUntilFinish();
  }
}
