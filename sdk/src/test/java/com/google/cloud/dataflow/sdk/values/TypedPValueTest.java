/*
 * Copyright (C) 2015 Google Inc.
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

package com.google.cloud.dataflow.sdk.values;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.VarIntCoder;
import com.google.cloud.dataflow.sdk.testing.TestPipeline;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/**
 * Tests for {@link TypedPValue}, primarily focusing on Coder inference.
 */
@RunWith(JUnit4.class)
public class TypedPValueTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static class IdentityDoFn extends DoFn<Integer, Integer> {
    private static final long serialVersionUID = 0;
    @Override
    public void processElement(ProcessContext c) throws Exception {
      c.output(c.element());
    }
  }

  private static PCollectionTuple buildPCollectionTupleWithTags(
      TupleTag<Integer> mainOutputTag, TupleTag<Integer> sideOutputTag) {
    Pipeline p = TestPipeline.create();
    PCollection<Integer> input = p.apply(Create.of(1, 2, 3));
    PCollectionTuple tuple = input.apply(
        ParDo
        .withOutputTags(mainOutputTag, TupleTagList.of(sideOutputTag))
        .of(new IdentityDoFn()));
    return tuple;
  }

  private static <T> TupleTag<T> makeTagStatically() {
    return new TupleTag<T>() {};
  }

  @Test
  public void testUntypedSideOutputTupleTagGivesActionableMessage() {
    TupleTag<Integer> mainOutputTag = new TupleTag<Integer>() {};
    // untypedSideOutputTag did not use anonymous subclass.
    TupleTag<Integer> untypedSideOutputTag = new TupleTag<Integer>();
    PCollectionTuple tuple = buildPCollectionTupleWithTags(mainOutputTag, untypedSideOutputTag);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("No Coder has been manually specified");
    thrown.expectMessage("erasure");
    thrown.expectMessage("see TupleTag Javadoc");

    tuple.get(untypedSideOutputTag).getCoder();
  }

  @Test
  public void testStaticFactorySideOutputTupleTagGivesActionableMessage() {
    TupleTag<Integer> mainOutputTag = new TupleTag<Integer>() {};
    // untypedSideOutputTag constructed from a static factory method.
    TupleTag<Integer> untypedSideOutputTag = makeTagStatically();
    PCollectionTuple tuple = buildPCollectionTupleWithTags(mainOutputTag, untypedSideOutputTag);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("No Coder has been manually specified");
    thrown.expectMessage("erasure");
    thrown.expectMessage("see TupleTag Javadoc");

    tuple.get(untypedSideOutputTag).getCoder();
  }

  @Test
  public void testTypedSideOutputTupleTag() {
    TupleTag<Integer> mainOutputTag = new TupleTag<Integer>() {};
    // typedSideOutputTag was constructed with compile-time type information.
    TupleTag<Integer> typedSideOutputTag = new TupleTag<Integer>() {};
    PCollectionTuple tuple = buildPCollectionTupleWithTags(mainOutputTag, typedSideOutputTag);

    assertThat(tuple.get(typedSideOutputTag).getCoder(), instanceOf(VarIntCoder.class));
  }

  @Test
  public void testUntypedMainOutputTagTypedSideOutputTupleTag() {
    // mainOutputTag is allowed to be untyped because Coder can be inferred other ways.
    TupleTag<Integer> mainOutputTag = new TupleTag<>();
    TupleTag<Integer> typedSideOutputTag = new TupleTag<Integer>() {};
    PCollectionTuple tuple = buildPCollectionTupleWithTags(mainOutputTag, typedSideOutputTag);

    assertThat(tuple.get(typedSideOutputTag).getCoder(), instanceOf(VarIntCoder.class));
  }

  // A simple class for which there should be no obvious Coder.
  static class EmptyClass {
  }

  private static class EmptyClassDoFn extends DoFn<Integer, EmptyClass> {
    private static final long serialVersionUID = 0;
    @Override
    public void processElement(ProcessContext c) throws Exception {
      c.output(new EmptyClass());
    }
  }

  @Test
  public void testParDoWithNoSideOutputsErrorDoesNotMentionTupleTag() {
    Pipeline p = TestPipeline.create();
    PCollection<EmptyClass> input = p
        .apply(Create.of(1, 2, 3))
        .apply(ParDo.of(new EmptyClassDoFn()));

    try {
      input.getCoder();
    } catch (IllegalStateException exc) {
      String message = exc.getMessage();

      // Output specific to ParDo TupleTag side outputs should not be present.
      assertThat(message, not(containsString("erasure")));
      assertThat(message, not(containsString("see TupleTag Javadoc")));
      // Instead, expect output suggesting other possible fixes.
      assertThat(message,
          containsString("Building a Coder using a registered CoderFactory failed"));
      assertThat(message,
          containsString("Building a Coder from the @DefaultCoder annotation failed"));
      assertThat(message,
          containsString("Building a Coder from the fallback CoderProvider failed"));
      return;
    }
    fail("Should have thrown IllegalStateException due to failure to infer a coder.");
  }
}

