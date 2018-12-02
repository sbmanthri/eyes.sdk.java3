package com.applitools.eyes.visualGridClient.data;

import com.applitools.eyes.TestResults;
import com.applitools.eyes.visualGridClient.IEyesConnector;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class Task implements Callable<TestResults> {


    private static AtomicBoolean isThrown = new AtomicBoolean(false);


    public enum TaskType {OPEN, CHECK, CLOSE, ABORT}


    private TestResults testResults;
    private IEyesConnector eyesConnector;

    private TaskType type;
    private RenderStatusResults renderResult;
    private TaskListener runningTestListener;
    private RenderingConfiguration.RenderBrowserInfo browserInfo;
    private RenderingConfiguration configuration;

    interface TaskListener {

        void onTaskComplete(Task task);

    }


    public Task(TestResults testResults, IEyesConnector eyesConnector, TaskType type, RenderingConfiguration.RenderBrowserInfo browserInfo, RenderingConfiguration configuration, TaskListener runningTestListener) {
        this.testResults = testResults;
        this.eyesConnector = eyesConnector;
        this.type = type;
        this.runningTestListener = runningTestListener;
        this.browserInfo = browserInfo;
        this.configuration = configuration;
    }

    public RenderingConfiguration.RenderBrowserInfo getBrowserInfo() {
        return browserInfo;
    }

    public TaskType getType() {
        return type;
    }


    @Override
    public TestResults call() throws Exception {
        testResults = null;
            System.out.println("Task.run()");
            switch (type) {
                case OPEN:
                        System.out.println("Task.run opening task");
                        eyesConnector.open(configuration.getAppName(), configuration.getTestName());
                    break;
                case CHECK:
                    System.out.println("Task.call CHECK");

                    break;
                case CLOSE:
                    if(!Task.isThrown()){
                        isThrown.set(true);
                        throw new Exception("Michael's Exception! ");
                    }
                        testResults = eyesConnector.close(configuration.isThrowExceptionOn());
                    break;
                case ABORT:
                    eyesConnector.abortIfNotClosed();
            }
        //call the callback
        this.runningTestListener.onTaskComplete(this);
        return testResults;
    }

    public IEyesConnector getEyesConnector() {
        return eyesConnector;
    }

    private static boolean isThrown() {
        return Task.isThrown.get();
    }

    public void setRenderResult(RenderStatusResults renderResult) {
        this.renderResult = renderResult;
    }
    public boolean isTaskReadyToCheck() {
        return this.testResults != null;
    }
}
