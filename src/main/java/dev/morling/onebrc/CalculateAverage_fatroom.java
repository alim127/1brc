/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class CalculateAverage_fatroom {

    private static final String FILE = "./measurements.txt";

    private static class MeasurementAggregator {
        private double min;
        private double max;
        private double sum;
        private long count;

        public MeasurementAggregator() {
            this.min = 1000;
            this.max = -1000;
            this.sum = 0;
            this.count = 0;
        }

        public void consume(double value) {
            this.min = value > this.min ? this.min : value;
            this.max = this.max > value ? this.max : value;
            this.sum += value;
            this.count++;
        }

        public MeasurementAggregator combineWith(MeasurementAggregator that) {
            this.min = that.min > this.min ? this.min : that.min;
            this.max = this.max > that.max ? this.max : that.max;
            this.sum += that.sum;
            this.count += that.count;
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(min / 10.0).append("/").append(Math.round(sum / count) / 10.0).append("/").append(max / 10.0);
            return sb.toString();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        int SEGMENT_LENGTH = 256_000_000; // 256 MB

        RandomAccessFile file = new RandomAccessFile(FILE, "r");
        long fileSize = file.length();
        long position = 0;

        List<Callable<Map<Station, MeasurementAggregator>>> tasks = new LinkedList<>();
        while (position < fileSize) {
            long end = Math.min(position + SEGMENT_LENGTH, fileSize);
            int length = (int) (end - position);
            MappedByteBuffer buffer = file.getChannel().map(FileChannel.MapMode.READ_ONLY, position, length);
            while (buffer.get(length - 1) != '\n') {
                length--;
            }
            final int finalLength = length;
            tasks.add(() -> processBuffer(buffer, finalLength));
            position += length;
        }

        var executor = Executors.newFixedThreadPool(tasks.size());

        Map<String, MeasurementAggregator> aggregates = new TreeMap<>();
        for (var future : executor.invokeAll(tasks)) {
            var segmentAggregates = future.get();
            for (var entry : segmentAggregates.entrySet()) {
                aggregates.merge(entry.getKey().toString(), entry.getValue(), MeasurementAggregator::combineWith);
            }
        }
        executor.shutdown();

        // no sense to wait longer than base case
        executor.awaitTermination(5, TimeUnit.MINUTES);

        System.out.println(aggregates);
    }

    private static Map<Station, MeasurementAggregator> processBuffer(MappedByteBuffer source, int length) {

        Map<Station, MeasurementAggregator> aggregates = new HashMap<>(500);
        Station station;
        byte[] buffer = new byte[200];
        byte[] measurement = new byte[5];
        int measurementLength;
        int idx = 0;
        int hash = 1;
        for (int i = 0; i < length; ++i) {
            byte b = source.get(i);
            hash = 31 * hash + b;
            buffer[idx++] = b;
            if (b == ';') {
                station = new Station(hash, buffer, idx - 1);
                measurementLength = 3;
                measurement[0] = source.get(++i);
                measurement[1] = source.get(++i);
                measurement[2] = source.get(++i);
                measurement[3] = source.get(++i);
                if (measurement[3] != '\n') {
                    measurementLength++;
                    measurement[4] = source.get(++i);
                    if (measurement[4] != '\n') {
                        i++;
                        measurementLength++;
                    }
                }
                aggregates.computeIfAbsent(station, s -> new MeasurementAggregator()).consume(parseMeasurement(measurement, measurementLength));
                idx = 0;
                hash = 1;
            }
        }
        return aggregates;
    }

    static double parseMeasurement(byte[] source, int size) {
        int isNegativeSignPresent = ~(source[0] >> 4) & 1;
        int firstDigit = source[isNegativeSignPresent] - '0';
        int secondDigit = source[size - 3];
        int thirdDigit = source[size - 1];
        int has4 = (size - isNegativeSignPresent) >> 2;
        int value = has4 * firstDigit * 100 + secondDigit * 10 + thirdDigit - 528;
        return -isNegativeSignPresent ^ value - isNegativeSignPresent;
    }

    static class Station {
        private byte[] bytes;
        private int hash;

        public Station(int hash, byte[] bytes, int length) {
            this.bytes = new byte[length];
            System.arraycopy(bytes, 0, this.bytes, 0, length);
            this.hash = hash;
        }

        public String toString() {
            return new String(bytes, 0, bytes.length, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(Object o) {
            Station station = (Station) o;
            if (hash != station.hash)
                return false;
            return Arrays.equals(bytes, station.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
