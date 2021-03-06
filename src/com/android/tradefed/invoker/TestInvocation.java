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
package com.android.tradefed.invoker;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.ExistingBuildProvider;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.ILogRegistry;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.InvocationSummaryHelper;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IResumableTest;
import com.android.tradefed.testtype.IShardableTest;

import junit.framework.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Default implementation of {@link ITestInvocation}.
 * <p/>
 * Loads major objects based on {@link IConfiguration}
 *   - retrieves build
 *   - prepares target
 *   - runs tests
 *   - reports results
 */
public class TestInvocation implements ITestInvocation {

    static final String TRADEFED_LOG_NAME = "host_log";
    static final String DEVICE_LOG_NAME = "device_logcat";
    static final String BUILD_ERROR_BUGREPORT_NAME = "build_error_bugreport";

    private String mStatus = "(not invoked)";

    /**
     * A {@link ResultForwarder} for forwarding resumed invocations.
     * <p/>
     * It filters the invocationStarted event for the resumed invocation, and sums the invocation
     * elapsed time
     */
    private static class ResumeResultForwarder extends ResultForwarder {

        long mCurrentElapsedTime;

        /**
         * @param listeners
         */
        public ResumeResultForwarder(List<ITestInvocationListener> listeners,
                long currentElapsedTime) {
            super(listeners);
            mCurrentElapsedTime = currentElapsedTime;
        }

        @Override
        public void invocationStarted(IBuildInfo buildInfo) {
            // ignore
        }

        @Override
        public void invocationEnded(long newElapsedTime) {
            super.invocationEnded(mCurrentElapsedTime + newElapsedTime);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke(ITestDevice device, IConfiguration config, IRescheduler rescheduler)
            throws DeviceNotAvailableException {
        try {
            mStatus = "fetching build";
            config.getLogOutput().init();
            getLogRegistry().registerLogger(config.getLogOutput());
            IBuildInfo info = config.getBuildProvider().getBuild();
            if (info != null) {
                injectBuild(info, config.getTests());
                if (shardConfig(config, info, rescheduler)) {
                    CLog.i("Invocation for %s has been sharded, rescheduling",
                            device.getSerialNumber());
                } else {
                    device.setRecovery(config.getDeviceRecovery());
                    performInvocation(config, device, info, rescheduler);
                    // exit here, depend on performInvocation to deregister logger
                    return;
                }
            } else {
                mStatus = "(no build to test)";
                CLog.d("No build to test");
            }
        } catch (BuildRetrievalError e) {
            CLog.e(e);
            // report an empty invocation, so this error is sent to listeners
            startInvocation(config, device, e.getBuildInfo());
            // don't want to use #reportFailure, since that will call buildNotTested
            for (ITestInvocationListener listener : config.getTestInvocationListeners()) {
                listener.invocationFailed(e);
            }
            reportLogs(device, config.getTestInvocationListeners(), config.getLogOutput());
            InvocationSummaryHelper.reportInvocationEnded(
                    config.getTestInvocationListeners(), 0);
            return;
        } catch (IOException e) {
            CLog.e(e);
        }
        // save current log contents to global log
        getLogRegistry().dumpToGlobalLog(config.getLogOutput());
        getLogRegistry().unregisterLogger();
        config.getLogOutput().closeLog();
    }

    /**
     * Pass the build to any {@link IBuildReceiver} tests
     * @param buildInfo
     * @param tests
     */
    private void injectBuild(IBuildInfo buildInfo, List<IRemoteTest> tests) {
        for (IRemoteTest test : tests) {
            if (test instanceof IBuildReceiver) {
                ((IBuildReceiver)test).setBuild(buildInfo);
            }
        }
    }

    /**
     * Attempt to shard the configuration into sub-configurations, to be re-scheduled to run on
     * multiple resources in parallel.
     * <p/>
     * A successful shard action renders the current config empty, and invocation should not proceed.
     *
     * @see {@link IShardableTest}, {@link IRescheduler}
     *
     * @param config the current {@link IConfiguration}.
     * @param info the {@link IBuildInfo} to test
     * @param rescheduler the {@link IRescheduler}
     * @return true if test was sharded. Otherwise return <code>false</code>
     */
    private boolean shardConfig(IConfiguration config, IBuildInfo info, IRescheduler rescheduler) {
        mStatus = "sharding";
        List<IRemoteTest> shardableTests = new ArrayList<IRemoteTest>();
        boolean isSharded = false;
        for (IRemoteTest test : config.getTests()) {
            isSharded |= shardTest(shardableTests, test);
        }
        if (isSharded) {
            ShardMasterResultForwarder resultCollector = new ShardMasterResultForwarder(
                    config.getTestInvocationListeners(), shardableTests.size());
            ShardListener origConfigListener = new ShardListener(resultCollector);
            config.setTestInvocationListener(origConfigListener);
            // report invocation started using original buildinfo
            resultCollector.invocationStarted(info);
            for (IRemoteTest testShard : shardableTests) {
                CLog.i("Rescheduling sharded config...");
                IConfiguration shardConfig = config.clone();
                shardConfig.setTest(testShard);
                shardConfig.setBuildProvider(new ExistingBuildProvider(info.clone(),
                        config.getBuildProvider()));
                shardConfig.setTestInvocationListener(new ShardListener(resultCollector));
                shardConfig.setLogOutput(config.getLogOutput().clone());
                shardConfig.setCommandOptions(config.getCommandOptions().clone());
                // use the same {@link ITargetPreparer}, {@link IDeviceRecovery} etc as original
                // config
                rescheduler.scheduleConfig(shardConfig);
            }
            // clean up original build
            config.getBuildProvider().cleanUp(info);
            return true;
        }
        return false;
    }

    /**
     * Attempt to shard given {@link IRemoteTest}.
     *
     * @param shardableTests the list of {@link IRemoteTest}s to add to
     * @param test the {@link Test} to shard
     * @return <code>true</code> if test was sharded
     */
    private boolean shardTest(List<IRemoteTest> shardableTests, IRemoteTest test) {
        boolean isSharded = false;
        if (test instanceof IShardableTest) {
            IShardableTest shardableTest = (IShardableTest)test;
            Collection<IRemoteTest> shards = shardableTest.split();
            if (shards != null) {
                shardableTests.addAll(shards);
                isSharded = true;
            }
        }
        if (!isSharded) {
            shardableTests.add(test);
        }
        return isSharded;
    }

    /**
     * Display a log message informing the user of a invocation being started.
     *
     * @param info the {@link IBuildInfo}
     * @param device the {@link ITestDevice}
     */
    private void logStartInvocation(IBuildInfo info, ITestDevice device) {
        StringBuilder msg = new StringBuilder("Starting invocation for '");
        msg.append(info.getTestTag());
        msg.append("'");
        if (!info.getBuildId().equals(IBuildInfo.UNKNOWN_BUILD_ID)) {
            msg.append(" on build '");
            msg.append(info.getBuildId());
            msg.append("'");
        }
        for (String buildAttr : info.getBuildAttributes().values()) {
            msg.append(" ");
            msg.append(buildAttr);
        }
        msg.append(" on device ");
        msg.append(device.getSerialNumber());
        CLog.logAndDisplay(LogLevel.INFO, msg.toString());
        mStatus = String.format("running %s on build %s", info.getTestTag(), info.getBuildId());
    }

    /**
     * Performs the invocation
     *
     * @param config the {@link IConfiguration}
     * @param device the {@link ITestDevice} to use. May be <code>null</code>
     * @param info the {@link IBuildInfo}
     *
     * @throws DeviceNotAvailableException
     * @throws IOException if log could not be created
     * @throws ConfigurationException
     */
    private void performInvocation(IConfiguration config, ITestDevice device, IBuildInfo info,
            IRescheduler rescheduler) throws DeviceNotAvailableException, IOException {

        boolean resumed = false;
        long startTime = System.currentTimeMillis();
        long elapsedTime = -1;

        info.setDeviceSerial(device.getSerialNumber());
        startInvocation(config, device, info);
        try {
            device.setOptions(config.getDeviceOptions());
            for (ITargetPreparer preparer : config.getTargetPreparers()) {
                preparer.setUp(device, info);
            }
            runTests(device, info, config, rescheduler);
        } catch (BuildError e) {
            CLog.w("Build %s failed on device %s. Reason: %s", info.getBuildId(),
                    device.getSerialNumber(), e.toString());
            takeBugreport(device, config.getTestInvocationListeners());
            reportFailure(e, config.getTestInvocationListeners(), config.getBuildProvider(), info);
        } catch (TargetSetupError e) {
            CLog.e("Caught exception while running invocation");
            CLog.e(e);
            reportFailure(e, config.getTestInvocationListeners(), config.getBuildProvider(), info);
        } catch (DeviceNotAvailableException e) {
            // log a warning here so its captured before reportLogs is called
            CLog.w("Invocation did not complete due to device %s becoming not available. " +
                    "Reason: %s", device.getSerialNumber(), e.getMessage());
            resumed = resume(config, info, rescheduler, System.currentTimeMillis() - startTime);
            if (!resumed) {
                reportFailure(e, config.getTestInvocationListeners(), config.getBuildProvider(),
                        info);
            } else {
                CLog.i("Rescheduled failed invocation for resume");
            }
            throw e;
        } catch (RuntimeException e) {
            // log a warning here so its captured before reportLogs is called
            CLog.w("Unexpected exception when running invocation: %s", e.toString());
            reportFailure(e, config.getTestInvocationListeners(), config.getBuildProvider(), info);
            throw e;
        } finally {
            mStatus = "done running tests";
            try {
                reportLogs(device, config.getTestInvocationListeners(), config.getLogOutput());
                elapsedTime = System.currentTimeMillis() - startTime;
                if (!resumed) {
                    InvocationSummaryHelper.reportInvocationEnded(
                            config.getTestInvocationListeners(), elapsedTime);
                }
            } finally {
                config.getBuildProvider().cleanUp(info);
            }
        }
    }

    /**
     * Starts the invocation.
     * <p/>
     * Starts logging, and informs listeners that invocation has been started.
     *
     * @param config
     * @param device
     * @param info
     * @throws IOException if logger fails to initialize
     */
    private void startInvocation(IConfiguration config, ITestDevice device, IBuildInfo info) {
        logStartInvocation(info, device);
        for (ITestInvocationListener listener : config.getTestInvocationListeners()) {
            try {
                listener.invocationStarted(info);
            } catch (RuntimeException e) {
                // don't let one listener leave the invocation in a bad state
                CLog.e("Caught runtime exception from ITestInvocationListener");
                CLog.e(e);
            }
        }
    }

    /**
     * Attempt to reschedule the failed invocation to resume where it left off.
     * <p/>
     * @see {@link IResumableTest}
     *
     * @param config
     * @return <code>true</code> if invocation was resumed successfully
     */
    private boolean resume(IConfiguration config, IBuildInfo info, IRescheduler rescheduler,
            long elapsedTime) {
        for (IRemoteTest test : config.getTests()) {
            if (test instanceof IResumableTest) {
                IResumableTest resumeTest = (IResumableTest)test;
                if (resumeTest.isResumable()) {
                    // resume this config if any test is resumable
                    IConfiguration resumeConfig = config.clone();
                    // reuse the same build for the resumed invocation
                    IBuildInfo clonedBuild = info.clone();
                    resumeConfig.setBuildProvider(new ExistingBuildProvider(clonedBuild,
                            config.getBuildProvider()));
                    // create a result forwarder, to prevent sending two invocationStarted events
                    resumeConfig.setTestInvocationListener(new ResumeResultForwarder(
                            config.getTestInvocationListeners(), elapsedTime));
                    resumeConfig.setLogOutput(config.getLogOutput().clone());
                    resumeConfig.setCommandOptions(config.getCommandOptions().clone());
                    boolean canReschedule = rescheduler.scheduleConfig(resumeConfig);
                    if (!canReschedule) {
                        CLog.i("Cannot reschedule resumed config for build %s. Cleaning up build.",
                                info.getBuildId());
                        resumeConfig.getBuildProvider().cleanUp(clonedBuild);
                    }
                    // FIXME: is it a bug to return from here, when we may not have completed the
                    // FIXME: config.getTests iteration?
                    return canReschedule;
                }
            }
        }
        return false;
    }

    private void reportFailure(Throwable exception, List<ITestInvocationListener> listeners,
            IBuildProvider buildProvider, IBuildInfo info) {

        for (ITestInvocationListener listener : listeners) {
            listener.invocationFailed(exception);
        }
        if (!(exception instanceof BuildError)) {
            buildProvider.buildNotTested(info);
        }
    }

    private void reportLogs(ITestDevice device, List<ITestInvocationListener> listeners,
            ILeveledLogOutput logger) {
        InputStreamSource logcatSource = null;
        InputStreamSource globalLogSource = logger.getLog();
        if (device != null) {
            logcatSource = device.getLogcat();
        }

        for (ITestInvocationListener listener : listeners) {
            if (logcatSource != null) {
                listener.testLog(DEVICE_LOG_NAME, LogDataType.TEXT, logcatSource);
            }
            listener.testLog(TRADEFED_LOG_NAME, LogDataType.TEXT, globalLogSource);
        }

        // Clean up after our ISSen
        if (logcatSource != null) {
            logcatSource.cancel();
        }
        globalLogSource.cancel();

        // once tradefed log is reported, all further log calls for this invocation can get lost
        // unregister logger so future log calls get directed to the tradefed global log
        getLogRegistry().unregisterLogger();
        logger.closeLog();
    }

    private void takeBugreport(ITestDevice device, List<ITestInvocationListener> listeners) {
        if (device == null) {
            return;
        }

        InputStreamSource bugreport = device.getBugreport();
        try {
            for (ITestInvocationListener listener : listeners) {
                listener.testLog(BUILD_ERROR_BUGREPORT_NAME, LogDataType.TEXT, bugreport);
            }
        } finally {
            bugreport.cancel();
        }
    }

    /**
     * Gets the {@link ILogRegistry} to use.
     * <p/>
     * Exposed for unit testing.
     */
    ILogRegistry getLogRegistry() {
        return LogRegistry.getLogRegistry();
    }

    /**
     * Runs the test.
     *
     * @param device the {@link ITestDevice} to run tests on
     * @param buildInfo the {@link BuildInfo} describing the build target
     * @param tests the {@link Test}s to run
     * @param listeners the {@link ITestInvocationListener}s that listens for test results in real
     *            time
     * @throws DeviceNotAvailableException
     */
    private void runTests(ITestDevice device, IBuildInfo buildInfo, IConfiguration config,
            IRescheduler rescheduler)
            throws DeviceNotAvailableException {
        List<ITestInvocationListener> listeners = config.getTestInvocationListeners();
        for (IRemoteTest test : config.getTests()) {
            if (test instanceof IDeviceTest) {
                ((IDeviceTest)test).setDevice(device);
            }
            test.run(new ResultForwarder(listeners));
        }
    }

    @Override
    public String toString() {
        return mStatus;
    }
}
