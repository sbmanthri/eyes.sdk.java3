package com.applitools.eyes.visualGridClient.services;

import com.applitools.eyes.BatchInfo;
import com.applitools.eyes.TestResults;
import com.applitools.eyes.visualGridClient.model.TestResultContainer;

import java.util.List;
import java.util.concurrent.Future;

public interface IRenderingEyes {

    boolean isEyesClosed();

    RunningTest getNextTestToClose();

    void setListener(EyesListener listener);

    ScoreTask getBestScoreTaskForCheck();

    ScoreTask getBestScoreTaskForOpen();

    void setBatch(BatchInfo batchInfo);

    List<Future<TestResultContainer>> close();

    interface EyesListener{

        void onTaskComplete(Task task, IRenderingEyes eyes);

        void onRenderComplete();
    }

    List<RunningTest> getAllRunningTests();
}
