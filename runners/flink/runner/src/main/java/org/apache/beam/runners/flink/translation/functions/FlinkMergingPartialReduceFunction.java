/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink.translation.functions;

import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.CombineFnBase;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.OutputTimeFn;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.PerKeyCombineFnRunner;
import org.apache.beam.sdk.util.PerKeyCombineFnRunners;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.apache.flink.util.Collector;
import org.joda.time.Instant;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Special version of {@link FlinkPartialReduceFunction} that supports merging windows. This
 * assumes that the windows are {@link IntervalWindow IntervalWindows} and exhibits the
 * same behaviour as {@code MergeOverlappingIntervalWindows}.
 */
public class FlinkMergingPartialReduceFunction<K, InputT, AccumT, W extends IntervalWindow>
  extends FlinkPartialReduceFunction<K, InputT, AccumT, W> {

  public FlinkMergingPartialReduceFunction(
      CombineFnBase.PerKeyCombineFn<K, InputT, AccumT, ?> combineFn,
      WindowingStrategy<?, W> windowingStrategy,
      Map<PCollectionView<?>, WindowingStrategy<?, ?>> sideInputs,
      PipelineOptions pipelineOptions) {
    super(combineFn, windowingStrategy, sideInputs, pipelineOptions);
  }

  @Override
  public void combine(
      Iterable<WindowedValue<KV<K, InputT>>> elements,
      Collector<WindowedValue<KV<K, AccumT>>> out) throws Exception {

    FlinkProcessContext<KV<K, InputT>, KV<K, AccumT>> processContext =
        new FlinkProcessContext<>(
            serializedOptions.getPipelineOptions(),
            getRuntimeContext(),
            doFn,
            windowingStrategy,
            out,
            sideInputs);

    PerKeyCombineFnRunner<K, InputT, AccumT, ?> combineFnRunner =
        PerKeyCombineFnRunners.create(combineFn);

    @SuppressWarnings("unchecked")
    OutputTimeFn<? super BoundedWindow> outputTimeFn =
        (OutputTimeFn<? super BoundedWindow>) windowingStrategy.getOutputTimeFn();

    // get all elements so that we can sort them, has to fit into
    // memory
    // this seems very unprudent, but correct, for now
    List<WindowedValue<KV<K, InputT>>> sortedInput = Lists.newArrayList();
    for (WindowedValue<KV<K, InputT>> inputValue: elements) {
      for (WindowedValue<KV<K, InputT>> exploded: inputValue.explodeWindows()) {
        sortedInput.add(exploded);
      }
    }
    Collections.sort(sortedInput, new Comparator<WindowedValue<KV<K, InputT>>>() {
      @Override
      public int compare(
          WindowedValue<KV<K, InputT>> o1,
          WindowedValue<KV<K, InputT>> o2) {
        return Iterables.getOnlyElement(o1.getWindows()).maxTimestamp()
            .compareTo(Iterables.getOnlyElement(o2.getWindows()).maxTimestamp());
      }
    });

    // merge windows, we have to do it in an extra pre-processing step and
    // can't do it as we go since the window of early elements would not
    // be correct when calling the CombineFn
    mergeWindow(sortedInput);

    // iterate over the elements that are sorted by window timestamp
    final Iterator<WindowedValue<KV<K, InputT>>> iterator = sortedInput.iterator();

    // create accumulator using the first elements key
    WindowedValue<KV<K, InputT>> currentValue = iterator.next();
    K key = currentValue.getValue().getKey();
    IntervalWindow currentWindow =
        (IntervalWindow) Iterables.getOnlyElement(currentValue.getWindows());
    InputT firstValue = currentValue.getValue().getValue();
    processContext = processContext.forWindowedValue(currentValue);
    AccumT accumulator = combineFnRunner.createAccumulator(key, processContext);
    accumulator = combineFnRunner.addInput(key, accumulator, firstValue, processContext);

    // we use this to keep track of the timestamps assigned by the OutputTimeFn
    Instant windowTimestamp =
        outputTimeFn.assignOutputTime(currentValue.getTimestamp(), currentWindow);

    while (iterator.hasNext()) {
      WindowedValue<KV<K, InputT>> nextValue = iterator.next();
      IntervalWindow nextWindow = (IntervalWindow) Iterables.getOnlyElement(nextValue.getWindows());

      if (currentWindow.equals(nextWindow)) {
        // continue accumulating and merge windows

        InputT value = nextValue.getValue().getValue();
        processContext = processContext.forWindowedValue(nextValue);
        accumulator = combineFnRunner.addInput(key, accumulator, value, processContext);

        windowTimestamp = outputTimeFn.combine(
            windowTimestamp,
            outputTimeFn.assignOutputTime(nextValue.getTimestamp(), currentWindow));

      } else {
        // emit the value that we currently have
        out.collect(
            WindowedValue.of(
                KV.of(key, accumulator),
                windowTimestamp,
                currentWindow,
                PaneInfo.NO_FIRING));

        currentWindow = nextWindow;
        InputT value = nextValue.getValue().getValue();
        processContext = processContext.forWindowedValue(nextValue);
        accumulator = combineFnRunner.createAccumulator(key, processContext);
        accumulator = combineFnRunner.addInput(key, accumulator, value, processContext);
        windowTimestamp = outputTimeFn.assignOutputTime(nextValue.getTimestamp(), currentWindow);
      }
    }

    // emit the final accumulator
    out.collect(
        WindowedValue.of(
            KV.of(key, accumulator),
            windowTimestamp,
            currentWindow,
            PaneInfo.NO_FIRING));
  }

  /**
   * Merge windows. This assumes that the list of elements is sorted by window-end timestamp.
   * This replaces windows in the input list.
   */
  private void mergeWindow(List<WindowedValue<KV<K, InputT>>> elements) {
    int currentStart = 0;
    IntervalWindow currentWindow =
        (IntervalWindow) Iterables.getOnlyElement(elements.get(0).getWindows());

    for (int i = 1; i < elements.size(); i++) {
      WindowedValue<KV<K, InputT>> nextValue = elements.get(i);
      IntervalWindow nextWindow =
          (IntervalWindow) Iterables.getOnlyElement(nextValue.getWindows());
      if (currentWindow.intersects(nextWindow)) {
        // we continue
        currentWindow = currentWindow.span(nextWindow);
      } else {
        // retrofit the merged window to all windows up to "currentStart"
        for (int j = i - 1; j >= currentStart; j--) {
          WindowedValue<KV<K, InputT>> value = elements.get(j);
          elements.set(
              j,
              WindowedValue.of(
                  value.getValue(), value.getTimestamp(), currentWindow, value.getPane()));
        }
        currentStart = i;
        currentWindow = nextWindow;
      }
    }
    if (currentStart < elements.size() - 1) {
      // we have to retrofit the last batch
      for (int j = elements.size() - 1; j >= currentStart; j--) {
        WindowedValue<KV<K, InputT>> value = elements.get(j);
        elements.set(
            j,
            WindowedValue.of(
                value.getValue(), value.getTimestamp(), currentWindow, value.getPane()));
      }
    }
  }
}
