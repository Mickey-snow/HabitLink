package com.habit.server.scheduler;

import com.habit.server.service.TaskAutoResetService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * タスク自動再設定の定期実行スケジューラー
 *  タスクの自動再設定を定期的に実行する。
 * 期限切れタスクの検出が目的
 */
public class TaskAutoResetScheduler {
    private static final Logger logger = LoggerFactory.getLogger(TaskAutoResetScheduler.class);
    private final TaskAutoResetService taskAutoResetService;
    private final ScheduledExecutorService scheduler;
    
    public TaskAutoResetScheduler(TaskAutoResetService taskAutoResetService) {
        this.taskAutoResetService = taskAutoResetService;
        this.scheduler = Executors.newScheduledThreadPool(1); // スレッドプールのサイズは1（単一スレッドで実行）
    }
    
    /**
     * スケジューラーの開始メソッド
     * 毎日午前0時に実行されるように設定
     */
    public void start() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0); // 次の午前0時
        long initialDelay = now.until(nextRun, ChronoUnit.SECONDS); // 初回実行までの遅延（秒単位）
        long period = TimeUnit.DAYS.toSeconds(1); // 24時間

        logger.info("=== Initializing Task Auto Reset Scheduler ===");
        logger.info("Current time: " + now);
        logger.info("Next run: " + nextRun);
        logger.info("Initial delay: " + initialDelay + " seconds (" + (initialDelay / 3600) + "h" + ((initialDelay % 3600) / 60) + "m)");
        logger.info("Execution interval: every 24 hours");

        try {
            scheduler.scheduleAtFixedRate(
                this::executeAutoReset, // 実行するメソッド
                initialDelay,           // 初回実行までの遅延(次の午前0時まで)
                period,                 // 実行間隔(24時間ごと)
                TimeUnit.SECONDS        // 時間単位
            );
            
            logger.info("[SUCCESS] Task auto reset scheduler started successfully.");
            
        } catch (Exception e) {
            logger.error("[ERROR] Failed to start task auto reset scheduler: " + e.getMessage());
            e.printStackTrace();
            throw e; // 初期化失敗を上位に伝える
        }
        
        logger.info("=== Scheduler initialization complete ===");
    }
    
    /**
     * スケジューラーの停止メソッド
     * 1. 新しいタスクの受付を停止
     * 2. 60秒間実行中のタスクの完了を待機
     * 3. 完了しない場合は強制終了
     *
     * 【呼び出しタイミング】
     * サーバーシャットダウン時（HabitServer.javaのShutdownHookから）
     */
    public void stop() {
        scheduler.shutdown(); // 新しいタスクの受付を停止
        try {
            // 60秒間実行中のタスクの完了を待機
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow(); // 強制終了
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow(); // 割り込み時も強制終了
        }
        logger.info("Task auto reset scheduler stopped.");
    }
    
    /**
     * 自動再設定を実行するメソッド
     *  TaskAutoResetService.runScheduledCheck()を呼び出し、全チームのタスクを自動再設定
     *  例外が発生してもスケジューラーは停止せず、次回実行を継続
     */
    private void executeAutoReset() {
        LocalDateTime startTime = LocalDateTime.now();
        logger.info("=== Starting task auto reset ===");
        logger.info("Start time: " + startTime);
        logger.info("Thread: " + Thread.currentThread().getName());
        
        try {
            // メイン処理を実行
            taskAutoResetService.runScheduledCheck();
            
            LocalDateTime endTime = LocalDateTime.now();
            logger.info("=== Task auto reset completed successfully ===");
            logger.info("End time: " + endTime);
            logger.info("Elapsed time: " + java.time.Duration.between(startTime, endTime).toMillis() + "ms");
            
        } catch (Exception e) {
            LocalDateTime errorTime = LocalDateTime.now();
            logger.error("=== Error occurred during task auto reset ===");
            logger.error("Error time: " + errorTime);
            logger.error("Error: " + e.getMessage());
            logger.error("Stack trace:");
            e.printStackTrace();
            
            // エラー後も次回実行を継続するため、ここで例外を再スローしない
        }
        
        logger.info("=== Task auto reset process finished ===");
    }
    
    /**
     * 手動実行（テスト用）
     *
     * 【用途】
     * - 開発時のテスト
     * - デバッグ時の動作確認
     * - TaskAutoResetControllerのAPIから呼び出し
     *
     * 【注意】
     * スケジューラーの定期実行とは独立して実行される
     */
    public void executeNow() {
        executeAutoReset();
    }
    
    /**
     * デバッグ用：指定秒後に自動実行をテストする
     *
     * @param delaySeconds 実行までの遅延秒数
     */
    public void scheduleTestExecution(int delaySeconds) {
        logger.info("=== Scheduling debug test run ===");
        logger.info("Scheduled to run after " + delaySeconds + " seconds");
        logger.info("Scheduled time: " + LocalDateTime.now().plusSeconds(delaySeconds));
        
        scheduler.schedule(() -> {
            logger.info("=== Debug test run started ===");
            executeAutoReset();
            logger.info("=== Debug test run finished ===");
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
