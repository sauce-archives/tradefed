/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tradefed.util;

/**
 * Interface for running timed operations.
 */
public interface IRunUtil {

    /**
     * An interface for asynchronously executing an operation that returns a boolean status.
     */
    public static interface IRunnableResult {
        /**
         * Execute the operation.
         *
         * @return <code>true</code> if operation is performed successfully, <code>false</code>
         *         otherwise
         * @throws Exception if operation terminated abnormally
         */
        public boolean run() throws Exception;

        /**
         * Cancel the operation.
         */
        public void cancel();
    }

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time.
     *
     * @param timeout maximum time to wait in ms
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmd(final long timeout, final String... command);

    /**
     * Block and executes an operation, aborting if it takes longer than a specified time.
     *
     * @param timeout maximum time to wait in ms
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return the {@link CommandStatus} result of operation.
     */
    public CommandStatus runTimed(long timeout, IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful.
     *
     * @param opTimeout maximum time to wait in ms for one operation attempt
     * @param pollInterval time to wait between command retries
     * @param attempts the maximum number of attempts to try
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before attempts reached.
     */
    public boolean runTimedRetry(long opTimeout, long pollInterval, int attempts,
            IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful.
     *
     * @param opTimeout maximum time to wait in ms for a single operation attempt
     * @param pollInterval initial time to wait between operation attempts
     * @param maxTime the total approximate maximum time to keep trying the operation
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before maxTime expired
     */
    public boolean runFixedTimedRetry(final long opTimeout, final long pollInterval,
            final long maxTime, final IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful.
     * <p/>
     * Exponentially increase the wait time between operation attempts. This is intended to be used
     * when performing an operation such as polling a server, to give it time to recover in case it
     * is temporarily down.
     *
     * @param opTimeout maximum time to wait in ms for a single operation attempt
     * @param initialPollInterval initial time to wait between operation attempts
     * @param maxPollInterval the max time to wait between operation attempts
     * @param maxTime the total approximate maximum time to keep trying the operation
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before maxTime expired
     */
    public boolean runEscalatingTimedRetry(final long opTimeout, final long initialPollInterval,
            final long maxPollInterval, final long maxTime, final IRunUtil.IRunnableResult
            runnable);

    /**
     * Helper method to sleep for given time, ignoring any exceptions.
     *
     * @param time ms to sleep
     */
    public void sleep(long time);

}