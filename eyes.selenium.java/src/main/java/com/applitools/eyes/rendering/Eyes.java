package com.applitools.eyes.rendering;

import com.applitools.ICheckRGSettings;
import com.applitools.ICheckSettings;
import com.applitools.eyes.*;
import com.applitools.eyes.visualGridClient.services.*;
import com.applitools.eyes.visualGridClient.model.*;
import com.applitools.utils.ArgumentGuard;
import com.applitools.utils.GeneralUtils;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class Eyes implements IRenderingEyes, IEyes {

    private Logger logger;

    private String apiKey;
    private String serverUrl;

    private final VisualGridManager renderingGridManager;
    private List<RunningTest> testList = new ArrayList<>();
    private final List<RunningTest> testsInCloseProcess = Collections.synchronizedList(new ArrayList<RunningTest>());
    private AtomicBoolean isEyesClosed = new AtomicBoolean(false);
    private AtomicBoolean isEyesIssuedOpenTasks = new AtomicBoolean(false);
    private IRenderingEyes.EyesListener listener;
    private AbstractProxySettings proxy;

    private String PROCESS_RESOURCES;
    private JavascriptExecutor jsExecutor;
    private RenderingInfo renderingInfo;
    private IEyesConnector eyesConnector;
    private BatchInfo batchInfo = new BatchInfo(null);

    private IDebugResourceWriter debugResourceWriter;
    private String url;
    private List<Future<TestResultContainer>> futures = null;
    private String branchName = null;
    private String parentBranchName = null;
    private boolean hideCaret = false;
    private Boolean isDisabled;
    private MatchLevel matchLevel = MatchLevel.STRICT;

    {
        try {
            PROCESS_RESOURCES = GeneralUtils.readToEnd(Eyes.class.getResourceAsStream("/processPageAndSerialize.js"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private RunningTest.RunningTestListener testListener = new RunningTest.RunningTestListener() {
        @Override
        public void onTaskComplete(Task task, RunningTest test) {
            switch (task.getType()) {
                case CLOSE:
                case ABORT:
                    boolean isEyesClosed = true;
                    for (RunningTest runningTest : testList) {
                        isEyesClosed &= runningTest.isTestClose();
                    }
                    Eyes.this.isEyesClosed.set(isEyesClosed);
                    break;
                case OPEN:

            }

            if (Eyes.this.listener != null) {
                Eyes.this.listener.onTaskComplete(task, Eyes.this);
            }
        }

        @Override
        public void onRenderComplete() {
            logger.verbose("enter");
            Eyes.this.listener.onRenderComplete();
            logger.verbose("exit");
        }
    };

    public Eyes(VisualGridManager renderingGridManager) {
        ArgumentGuard.notNull(renderingGridManager, "renderingGridManager");
        this.renderingGridManager = renderingGridManager;
        this.logger = renderingGridManager.getLogger();
    }

    /**
     * Sets a handler of log messages generated by this API.
     * @param logHandler Handles log messages generated by this API.
     */
    @Override
    public void setLogHandler(LogHandler logHandler) {
        if (getIsDisabled()) return;
        LogHandler currentLogHandler = logger.getLogHandler();
        this.logger = new Logger();
        this.logger.setLogHandler(new MultiLogHandler(currentLogHandler, logHandler));

        if (currentLogHandler.isOpen() && !logHandler.isOpen()) {
            logHandler.open();
        }
    }

    public void open(WebDriver webDriver, RenderingConfiguration renderingConfiguration) {
        if (getIsDisabled()) return;
        logger.verbose("enter");

        ArgumentGuard.notNull(webDriver, "webDriver");
        ArgumentGuard.notNull(renderingConfiguration, "renderingConfiguration");

        initDriver(webDriver);

        if (renderingConfiguration.getBatch() == null) {
            renderingConfiguration.setBatch(batchInfo);
        }

        logger.verbose("getting all browsers info...");
        List<RenderBrowserInfo> browserInfoList = renderingConfiguration.getBrowsersInfo();
        logger.verbose("creating test descriptors for each browser info...");
        for (RenderBrowserInfo browserInfo : browserInfoList) {
            logger.verbose("creating test descriptor");
            RunningTest test = new RunningTest(createEyesConnector(browserInfo), renderingConfiguration, browserInfo, logger, testListener);
            this.testList.add(test);
        }

        logger.verbose(String.format("opening %d tests...", testList.size()));
        this.renderingGridManager.open(this, renderingInfo);
        logger.verbose("done");
    }

    private IEyesConnector createEyesConnector(RenderBrowserInfo browserInfo) {
        logger.verbose("creating eyes server connector");
        IEyesConnector eyesConnector = new EyesConnector(browserInfo, renderingGridManager.getRateLimiter());
        eyesConnector.setLogHandler(this.logger.getLogHandler());
        eyesConnector.setProxy(this.proxy);
        eyesConnector.setBatch(batchInfo);
        eyesConnector.setBranchName(this.branchName);
        eyesConnector.setParentBranchName(parentBranchName);
        eyesConnector.setHideCaret(this.hideCaret);
        eyesConnector.setMatchLevel(matchLevel);

        String serverUrl = this.serverUrl;
        if (serverUrl == null) {
            serverUrl = this.renderingGridManager.getServerUrl();
        }
        if (serverUrl != null) {
            try {
                eyesConnector.setServerUrl(serverUrl);
            } catch (URISyntaxException e) {
                GeneralUtils.logExceptionStackTrace(logger, e);
            }
        }

        String apiKey = this.apiKey;
        if (apiKey == null) {
            apiKey = renderingGridManager.getApiKey();
        }
        if (apiKey != null) {
            eyesConnector.setApiKey(apiKey);
        } else {
            throw new EyesException("Missing API key");
        }

        if (this.renderingInfo == null) {
            logger.verbose("initializing rendering info...");
            this.renderingInfo = eyesConnector.getRenderingInfo();
        }
        eyesConnector.setRenderInfo(this.renderingInfo);

        this.eyesConnector = eyesConnector;
        return eyesConnector;
    }

    private void initDriver(WebDriver webDriver) {
        if (webDriver instanceof JavascriptExecutor) {
            this.jsExecutor = (JavascriptExecutor) webDriver;
        }
        String currentUrl = webDriver.getCurrentUrl();
        this.url = currentUrl;
    }

    public RunningTest getNextTestToClose() {
        synchronized (testsInCloseProcess) {
            for (RunningTest runningTest : testList) {
                if (!runningTest.isTestClose() && runningTest.isTestReadyToClose() && !this.testsInCloseProcess.contains(runningTest)) {
                    this.testsInCloseProcess.add(runningTest);
                    return runningTest;
                }
            }
        }
        return null;
    }

    public TestResults close() {
        if (getIsDisabled()) return null;
        futures = closeAndReturnResults();
        return null;
    }

    @Override
    public TestResults close(boolean throwException) {
        if (getIsDisabled()) return null;
        futures = closeAndReturnResults();
        return null;
    }

    @Override
    public TestResults abortIfNotClosed() {
        return null; // TODO - implement?
    }

    @Override
    public boolean getIsOpen() {
        return !isEyesClosed();
    }

    @Override
    public String getApiKey() {
        return this.apiKey;
    }

    @Override
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void setIsDisabled(boolean disabled) {
        this.isDisabled = disabled;
    }

    @Override
    public boolean getIsDisabled() {
        return this.isDisabled == null ? this.renderingGridManager.getIsDisabled() : this.isDisabled;
    }

    @Override
    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    @Override
    public void setParentBranchName(String branchName) {
        this.parentBranchName = branchName;
    }

    @Override
    public void setHideCaret(boolean hideCaret) {
        this.hideCaret = hideCaret;
    }

    @Override
    public void setMatchLevel(MatchLevel level) {
        this.matchLevel = level;
    }

    public List<Future<TestResultContainer>> closeAndReturnResults() {
        if (getIsDisabled()) return new ArrayList<>();
        if (this.futures != null) {
            return futures;
        }
        List<Future<TestResultContainer>> futureList;
        logger.verbose("enter " + batchInfo);
        futureList = new ArrayList<>();
        try {
            for (RunningTest runningTest : testList) {
                logger.verbose("running test name: " + runningTest.getConfiguration().getTestName());
                logger.verbose("is current running test open: " + runningTest.isTestOpen());
                logger.verbose("is current running test ready to close: " + runningTest.isTestReadyToClose());
                logger.verbose("is current running test closed: " + runningTest.isTestClose());
                if (!runningTest.isTestClose()) {
                    logger.verbose("closing current running test");
                    FutureTask<TestResultContainer> closeFuture = runningTest.close();
                    logger.verbose("adding closeFuture to futureList");
                    futureList.add(closeFuture);
                }
            }
            futures = futureList;
            this.renderingGridManager.close(this);
        } catch (Exception e) {
            GeneralUtils.logExceptionStackTrace(logger, e);
        }
        return futureList;
    }

    @Override
    public synchronized ScoreTask getBestScoreTaskForCheck() {

        int bestScore = -1;

        ScoreTask currentBest = null;
        for (RunningTest runningTest : testList) {

            List<Task> taskList = runningTest.getTaskList();

            Task task;
            synchronized (taskList) {
                if (taskList.isEmpty()) continue;

                task = taskList.get(0);
                if (!runningTest.isTestOpen() || task.getType() != Task.TaskType.CHECK || !task.isTaskReadyToCheck())
                    continue;
            }


            ScoreTask scoreTask = runningTest.getScoreTaskObjectByType(Task.TaskType.CHECK);

            if (scoreTask == null) continue;

            if (bestScore < scoreTask.getScore()) {
                currentBest = scoreTask;
                bestScore = scoreTask.getScore();
            }
        }
        return currentBest;
    }

    @Override
    public ScoreTask getBestScoreTaskForOpen() {
        int bestMark = -1;
        ScoreTask currentBest = null;
        for (RunningTest runningTest : testList) {

            ScoreTask currentScoreTask = runningTest.getScoreTaskObjectByType(Task.TaskType.OPEN);
            if (currentScoreTask == null) continue;

            if (bestMark < currentScoreTask.getScore()) {
                bestMark = currentScoreTask.getScore();
                currentBest = currentScoreTask;

            }
        }
        return currentBest;
    }

    @Override
    public void setBatch(BatchInfo batchInfo) {
        this.batchInfo = batchInfo;
    }

    @Override
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @Override
    public boolean isEyesClosed() {
        boolean isEyesClosed = true;
        for (RunningTest runningTest : testList) {
            isEyesClosed = isEyesClosed && runningTest.isTestClose();
        }
        return isEyesClosed;
    }

    public void setListener(EyesListener listener) {
        this.listener = listener;
    }

    /**
     * Sets the proxy settings to be used by the rest client.
     * @param abstractProxySettings The proxy settings to be used by the rest client.
     *                              If {@code null} then no proxy is set.
     */
    @Override
    public void setProxy(AbstractProxySettings abstractProxySettings) {
        this.proxy = abstractProxySettings;
    }

    public void check(String name, ICheckSettings checkSettings) {
        if (getIsDisabled()) return;
        ArgumentGuard.notNull(checkSettings, "checkSettings");
        checkSettings = checkSettings.withName(name);
        this.check(checkSettings);
    }

    public void check(ICheckSettings checkSettings) {
        if (getIsDisabled()) return;
        logger.verbose("enter");

        ArgumentGuard.notOfType(checkSettings, ICheckRGSettings.class, "checkSettings");

        List<Task> openTasks = addOpenTaskToAllRunningTest();

        List<Task> taskList = new ArrayList<>();

        String domCaptureScript = "var callback = arguments[arguments.length - 1]; return (" + PROCESS_RESOURCES + ")().then(JSON.stringify).then(callback, function(err) {callback(err.stack || err.toString())})";

        logger.verbose(" $$$$$$$$$$    Dom extraction starting   (" + checkSettings.toString() + ")   $$$$$$$$$$$$");
        String scriptResult = (String) this.jsExecutor.executeAsyncScript(domCaptureScript);

        logger.verbose(" $$$$$$$$$$    Dom extracted  (" + checkSettings.toString() + ")   $$$$$$$$$$$$");

        for (final RunningTest test : testList) {
            Task checkTask = test.check(checkSettings);
            taskList.add(checkTask);
        }

        logger.verbose(" $$$$$$$$$$    added check tasks  (" + checkSettings.toString() + ")   $$$$$$$$$$$$");

        ICheckRGSettings rgSettings = (ICheckRGSettings) checkSettings;

        this.renderingGridManager.check(rgSettings, debugResourceWriter, scriptResult,
                this.eyesConnector, taskList, openTasks,
                new VisualGridManager.RenderListener() {
                    @Override
                    public void onRenderSuccess() {

                    }

                    @Override
                    public void onRenderFailed(Exception e) {

                    }
                });

        logger.verbose(" $$$$$$$$$$    created renderTask  (" + checkSettings.toString() + ")   $$$$$$$$$$$$");
    }

    private synchronized List<Task> addOpenTaskToAllRunningTest() {
        logger.verbose("enter");
        List<Task> tasks = new ArrayList<>();
        if (!this.isEyesIssuedOpenTasks.get()) {
            for (RunningTest runningTest : testList) {
                Task task = runningTest.open();
                tasks.add(task);
            }
            logger.verbose("calling addOpenTaskToAllRunningTest.open");
            this.isEyesIssuedOpenTasks.set(true);
        }
        logger.verbose("exit");
        return tasks;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    public List<RunningTest> getAllRunningTests() {
        return testList;
    }

    public void setDebugResourceWriter(IDebugResourceWriter debugResourceWriter) {
        this.debugResourceWriter = debugResourceWriter;
    }

    @Override
    public String toString() {
        return "Eyes - url: " + url;
    }

    public List<Future<TestResultContainer>> getCloseFutures() {
        return futures;
    }
}
