/*
 * Autopsy Forensic Browser
 *
 * Copyright 2018 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.healthmonitor;

/**
 * Used to calculate and report timing metrics.
 */
public class TimingMetric {
    
    private final String name;
    private final long startingTimestamp;
    private Long duration;
    
    TimingMetric(String name) {
        this.name = name;
        this.startingTimestamp = System.nanoTime();
        this.duration = null;
    }
    
    /**
     * Record how long the metric was running.
     */
    void stopTiming() {
        long endingTimestamp = System.nanoTime();
        this.duration = endingTimestamp - startingTimestamp;
    }

    /**
     * Get the name of metric
     * @return name
     */
    String getName() {
        return name;
    }
    
    /**
     * Get the duration of the metric. Will throw an exception if the
     * metric has not been stopped.
     * @return how long the metric was running (nanoseconds)
     * @throws HealthMonitorException 
     */
    long getDuration() throws HealthMonitorException {
        if (duration != null) {
            return duration;
        } else {
            throw new HealthMonitorException("getDuration() called before stopTiming()");
        }
    }
}
