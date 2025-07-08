package com.habit.server.service;

import com.habit.domain.Message;
import com.habit.domain.Task;
import com.habit.domain.User;
import com.habit.domain.UserTaskStatus;
import com.habit.server.repository.MessageRepository;
import com.habit.server.repository.TaskRepository;
import com.habit.server.repository.UserRepository;
import com.habit.server.repository.UserTaskStatusRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * タスクの自動再設定サービス
 *  タスクが期限を過ぎても未完了の場合、次回サイクルのタスクを自動生成
 *  手動実行も可能（TaskAutoResetControllerのAPI経由）
 *  既に同じ日付のタスクが存在する場合は重複作成しない
 */
public class TaskAutoResetService {
    private static final Logger logger = LoggerFactory.getLogger(TaskAutoResetService.class);
    private final TaskRepository taskRepository;
    private final UserTaskStatusRepository userTaskStatusRepository;
    private final UserRepository userRepository; // UserRepositoryを追加
    private final MessageRepository messageRepository; // MessageRepositoryを追加
    private final Clock clock;
    private static final Path LAST_EXECUTION_FILE = Paths.get("last_execution.log");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    
    // 重複実行防止用のフラグ
    private volatile boolean isRunning = false;

    // システムメッセージ用の固定ユーザー
    private static final User SERVER_USER = new User("system", "System", "");

    /**
     * コンストラクタ
     */ 
    public TaskAutoResetService(TaskRepository taskRepository, UserTaskStatusRepository userTaskStatusRepository, UserRepository userRepository, MessageRepository messageRepository, Clock clock) {
        this.taskRepository = taskRepository;
        this.userTaskStatusRepository = userTaskStatusRepository;
        this.userRepository = userRepository; // UserRepositoryを初期化
        this.messageRepository = messageRepository; // MessageRepositoryを初期化
        this.clock = clock;
    }

    public void catchUpMissedExecutions() {
        LocalDate lastExecutionDate = loadLastExecutionTime();
        LocalDate today = LocalDate.now(clock);

        if (lastExecutionDate == null) {
            lastExecutionDate = today.minusDays(1); //
        }

        List<LocalDate> missedDates = lastExecutionDate.plusDays(1).datesUntil(today.plusDays(1))
                                       .collect(Collectors.toList());

        if (!missedDates.isEmpty()) {
            logger.info("Starting update of pending tasks during server downtime: " + missedDates.size() + " day(s)");
            for (LocalDate date : missedDates) {
                logger.info("Updating tasks for " + date + ".");
                runScheduledCheckForDate(date);
            }
            logger.info("Finished updating pending tasks.");
        }
    }
    
    /**
     * 定期実行用：全チームのタスクをチェック
     *  TaskAutoResetSchedulerにより毎日午前0時に自動実行
     *  TaskAutoResetControllerのAPIからも呼び出し可能
     *
     * 【処理フロー】
     * 1. 重複実行防止チェック
     * 2. 全チームIDを取得
     * 3. 各チームのタスクを順次チェック・再設定
     * 4. 実行結果をログ出力（処理チーム数、再設定タスク数）
     */
    public void runScheduledCheck() {
        runScheduledCheckForDate(LocalDate.now(clock));
    }

    public void runScheduledCheckForDate(LocalDate date) {
        // 重複実行防止（前回の処理がまだ終わっていない場合はスキップ）
        if (isRunning) {
            logger.info("Auto reset process already running. Skipping this run.");
            return;
        }
        
        isRunning = true;
        try {
            // TeamRepositoryから全チームIDを取得
            com.habit.server.repository.TeamRepository teamRepository =
                new com.habit.server.repository.TeamRepository();
            List<String> allTeamIds = teamRepository.findAllTeamIds();
            
            logger.info("Auto reset check started: " + allTeamIds.size() + " team(s) at " +
                java.time.LocalDateTime.now(clock));
            
            int processedTeams = 0;
            int totalResets = 0;
            
            // 各チームを順次処理
            for (String teamId : allTeamIds) {
                try {
                    int resets = checkAndResetTasks(teamId, date); // checkAndResetTasksWithCount から checkAndResetTasks に変更
                    totalResets += resets;
                    processedTeams++;
                } catch (Exception e) {
                    logger.error("Error during auto reset for team " + teamId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            logger.info("Auto reset check complete: processed " + processedTeams + " team(s), reset " +
                totalResets + " task(s) at " + java.time.LocalDateTime.now(clock));
            
            saveLastExecutionTime(date);
        } catch (Exception e) {
            logger.error("Error during scheduled auto reset: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 必ず実行フラグをリセット
            isRunning = false;
        }
    }
    
    /**
     * 指定チームの全タスクを自動再設定チェック
     *
     * @param teamId 対象チームID
     * @return 再設定されたタスクの数
     *
     * 【処理フロー】
     * 1. 指定チームの全タスクを取得
     * 2. 各タスクに対して再設定チェックを実施
     * 3. 対象タスクの全ユーザー分をチェック・再設定
     */
    public int checkAndResetTasks(String teamId, LocalDate executionDate) { // private から public に変更
        List<Task> teamTasks = taskRepository.findTeamTasksByTeamID(teamId);
        int resetCount = 0;
        
        logger.info("[DEBUG] Team " + teamId + " task count: " + teamTasks.size());
        logger.info("[DEBUG] Execution date: " + executionDate + ", target (yesterday): " + executionDate.minusDays(1));

        for (Task task : teamTasks) {
            logger.info("[DEBUG] Processing task: " + task.getTaskName() + " (ID: " + task.getTaskId() + ")");
            
            String cycleType = task.getCycleType();
            logger.info("[DEBUG] cycleType: " + cycleType);
            
            if (cycleType == null) {
                logger.info("[DEBUG] cycleType is null, skipping: " + task.getTaskName());
                continue; // 繰り返し設定のないタスクはスキップ
            }

            LocalDate dateToCheck = executionDate.minusDays(1); // チェック対象は実行日の前日
            LocalDate newDueDate = null;

            switch (cycleType.toLowerCase()) {
                case "daily":
                    newDueDate = executionDate;
                    break;
                case "weekly":
                    newDueDate = dateToCheck.plusWeeks(1); // 基準日（昨日）から1週間後
                    break;
                default:
                    logger.info("[DEBUG] Unsupported cycleType, skipping: " + cycleType + " (task: " + task.getTaskName() + ")");
                    continue; // 未対応のサイクルタイプはスキップ
            }

            logger.info("[DEBUG] New due date: " + newDueDate);

            // Task自体のdueDateを更新して保存
            task.setDueDate(newDueDate);
            taskRepository.save(task);

            // 昨日の日付でUserTaskStatusを検索
            List<UserTaskStatus> statusesToCheck = userTaskStatusRepository.findByTaskIdAndDate(task.getTaskId(), dateToCheck);
            logger.info("[DEBUG] UserTaskStatus count for " + dateToCheck + ": " + statusesToCheck.size());

            if (!statusesToCheck.isEmpty()) {
                logger.info("[INFO] Pending UserTaskStatus count for " + dateToCheck + ": " + statusesToCheck.size());
                
                for (UserTaskStatus oldStatus : statusesToCheck) {
                    logger.info("[DEBUG] Handling UserTaskStatus: userId=" + oldStatus.getUserId() +
                                     ", isDone=" + oldStatus.isDone() + ", teamId=" + oldStatus.getTeamId() +
                                     ", date=" + oldStatus.getDate());
                    
                    // isDoneを判定
                    com.habit.domain.User user = userRepository.findById(oldStatus.getUserId());
                    if (user != null) {
                        int currentPoints = user.getSabotagePoints();
                        int newPoints;
                        int changeAmount;

                        if(oldStatus.isDone()) {
                            // 完了していたらポイントを減らす（0未満にはしない）
                            newPoints = Math.max(0, currentPoints - 1);
                            changeAmount = newPoints - currentPoints;
                            logger.info("[INFO] Task completed, decreasing sabotage points for " + user.getUsername());
                        } else {
                            // 未完了ならポイントを増やす（9を超えない）
                            newPoints = Math.min(9, currentPoints + 1);
                            changeAmount = newPoints - currentPoints;
                            logger.info("[INFO] Task incomplete, increasing sabotage points for " + user.getUsername() + " and sending report message");

                            // サボり報告メッセージを送信
                            try {
                                String reportMessage = user.getUsername() + "さんが昨日のタスク「" + task.getTaskName() + "」をサボりました。";
                                Message systemMessage = new Message(SERVER_USER, oldStatus.getTeamId(), reportMessage, LocalDateTime.now(clock));
                                
                                logger.info("[DEBUG] Before saving message: " +
                                                  "sender=" + systemMessage.getSender().getUserId() +
                                                  ", teamId=" + oldStatus.getTeamId() +
                                                  ", content=" + systemMessage.getContent());
                                
                                messageRepository.save(systemMessage);
                                
                                logger.info("[SUCCESS] Sabotage report message sent: team=" + oldStatus.getTeamId() +
                                                  ", ユーザー=" + user.getUsername() +
                                                  ", タスク=" + task.getTaskName() +
                                                  ", 実行時刻=" + LocalDateTime.now(clock));
                            } catch (Exception messageException) {
                                logger.error("[ERROR] Failed to send sabotage report message: team=" + oldStatus.getTeamId() +
                                                  ", ユーザー=" + user.getUsername() +
                                                  ", タスク=" + task.getTaskName() +
                                                  ", エラー=" + messageException.getMessage());
                                messageException.printStackTrace();
                            }
                        }
                        
                        try {
                            user.setSabotagePoints(newPoints);
                            userRepository.save(user);
                            logger.info("[SUCCESS] Updated sabotage points for " + user.getUsername() + ": " + currentPoints + " -> " + newPoints + " (diff: " + (changeAmount > 0 ? "+" : "") + changeAmount + ")");
                        } catch (Exception userSaveException) {
                            logger.error("[ERROR] Failed to save sabotage points for user " + user.getUsername() + ": " + userSaveException.getMessage());
                            userSaveException.printStackTrace();
                        }
                    } else {
                        logger.error("[ERROR] User not found: userId=" + oldStatus.getUserId());
                    }

                    // 新しいdueDateでisDoneがfalseのUserTaskStatusを生成
                    UserTaskStatus newStatus = new UserTaskStatus(
                        oldStatus.getUserId(),
                        task.getTaskId(),
                        task.getTeamId(),
                        newDueDate,
                        false // 初期状態は未完了
                    );

                    // 重複チェック
                    Optional<UserTaskStatus> existingStatus = userTaskStatusRepository.findByUserIdAndTaskIdAndDate(
                        newStatus.getUserId(), newStatus.getTaskId(), newStatus.getDate());

                    if (existingStatus.isEmpty()) {
                        userTaskStatusRepository.save(newStatus);
                        resetCount++;
                        logger.info("Created new UserTaskStatus: userId=" + newStatus.getUserId() +
                            ", taskId=" + newStatus.getTaskId() +
                            ", date=" + newStatus.getDate());
                    } else {
                        logger.info("UserTaskStatus already exists, skipping: userId=" + newStatus.getUserId() +
                            ", taskId=" + newStatus.getTaskId() +
                            ", date=" + newStatus.getDate());
                    }
                }
            } else {
                logger.info("[DEBUG] No UserTaskStatus found for " + dateToCheck + ", skipping task \"" + task.getTaskName() + "\"");
            }
        }
        return resetCount;
    }

    /**
     * デバッグ用：「今日まで」の未消化タスクでサボり報告を送信
     * 通常の処理は「昨日まで」だが、デバッグ時は「今日まで」をチェック
     *
     * @param teamId 対象チームID
     * @param executionDate 実行日（今日の日付）
     * @return 処理されたタスクの数
     */
    public int checkAndReportTasksForToday(String teamId, LocalDate executionDate) {
        List<Task> teamTasks = taskRepository.findTeamTasksByTeamID(teamId);
        int processedCount = 0;
        
        logger.info("[DEBUG] Debug sabotage report check start: team " + teamId + " task count: " + teamTasks.size());
        logger.info("[DEBUG] Execution date: " + executionDate + ", target (today): " + executionDate);

        for (Task task : teamTasks) {
            logger.info("[DEBUG] Processing task: " + task.getTaskName() + " (ID: " + task.getTaskId() + ")");
            
            String cycleType = task.getCycleType();
            logger.info("[DEBUG] cycleType: " + cycleType);
            
            if (cycleType == null) {
                logger.info("[DEBUG] cycleType is null, skipping: " + task.getTaskName());
                continue; // 繰り返し設定のないタスクはスキップ
            }

            LocalDate dateToCheck = executionDate; // チェック対象は実行日（今日）

            // 今日の日付でUserTaskStatusを検索
            List<UserTaskStatus> statusesToCheck = userTaskStatusRepository.findByTaskIdAndDate(task.getTaskId(), dateToCheck);
            logger.info("[DEBUG] UserTaskStatus count for " + dateToCheck + ": " + statusesToCheck.size());

            if (!statusesToCheck.isEmpty()) {
                logger.info("[INFO] Pending UserTaskStatus count for " + dateToCheck + ": " + statusesToCheck.size());
                
                for (UserTaskStatus status : statusesToCheck) {
                    logger.info("[DEBUG] Handling UserTaskStatus: userId=" + status.getUserId() +
                                     ", isDone=" + status.isDone() + ", teamId=" + status.getTeamId() +
                                     ", date=" + status.getDate());
                    
                    // 未完了の場合のみサボり報告メッセージを送信
                    if (!status.isDone()) {
                        com.habit.domain.User user = userRepository.findById(status.getUserId());
                        if (user != null) {
                            logger.info("[INFO] Debug sabotage report: " + user.getUsername() + " did not complete task \"" + task.getTaskName() + "\"");

                            // サボり報告メッセージを送信
                            try {
                                String reportMessage = "[デバッグ] " + user.getUsername() + "さんが今日のタスク「" + task.getTaskName() + "」をサボりました。";
                                Message systemMessage = new Message(SERVER_USER, status.getTeamId(), reportMessage, LocalDateTime.now(clock));
                                
                                logger.info("[DEBUG] Before saving message: " +
                                                  "sender=" + systemMessage.getSender().getUserId() +
                                                  ", teamId=" + status.getTeamId() +
                                                  ", content=" + systemMessage.getContent());
                                
                                messageRepository.save(systemMessage);
                                
                                logger.info("[SUCCESS] Debug sabotage report message sent: team=" + status.getTeamId() +
                                                  ", ユーザー=" + user.getUsername() +
                                                  ", タスク=" + task.getTaskName() +
                                                  ", 実行時刻=" + LocalDateTime.now(clock));
                                processedCount++;
                            } catch (Exception messageException) {
                                logger.error("[ERROR] Failed to send debug sabotage report message: team=" + status.getTeamId() +
                                                  ", ユーザー=" + user.getUsername() +
                                                  ", タスク=" + task.getTaskName() +
                                                  ", エラー=" + messageException.getMessage());
                                messageException.printStackTrace();
                            }
                        } else {
                            logger.error("[ERROR] User not found: userId=" + status.getUserId());
                        }
                    } else {
                        logger.info("[DEBUG] Task already completed, skipping: userId=" + status.getUserId() + ", taskName=" + task.getTaskName());
                    }
                }
            } else {
                logger.info("[DEBUG] No UserTaskStatus found for " + dateToCheck + ", skipping task \"" + task.getTaskName() + "\"");
            }
        }
        return processedCount;
    }

    /**
     * デバッグ用：全チームの「今日まで」の未消化タスクでサボり報告を送信
     */
    public void runDebugSabotageReportForToday() {
        // 重複実行防止（前回の処理がまだ終わっていない場合はスキップ）
        if (isRunning) {
            logger.info("Auto reset process already running, skipping debug processing");
            return;
        }
        
        isRunning = true;
        try {
            LocalDate today = LocalDate.now(clock);
            
            // TeamRepositoryから全チームIDを取得
            com.habit.server.repository.TeamRepository teamRepository =
                new com.habit.server.repository.TeamRepository();
            List<String> allTeamIds = teamRepository.findAllTeamIds();
            
            logger.info("Debug sabotage report check start: " + allTeamIds.size() + " team(s) at " +
                java.time.LocalDateTime.now(clock));
            
            int processedTeams = 0;
            int totalReports = 0;
            
            // 各チームを順次処理
            for (String teamId : allTeamIds) {
                try {
                    int reports = checkAndReportTasksForToday(teamId, today);
                    totalReports += reports;
                    processedTeams++;
                } catch (Exception e) {
                    logger.error("Error in debug sabotage report for team " + teamId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            logger.info("Debug sabotage report check complete: processed " + processedTeams + " team(s), sent " +
                totalReports + " report(s) at " + java.time.LocalDateTime.now(clock));
            
        } catch (Exception e) {
            logger.error("Error executing debug sabotage report: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 必ず実行フラグをリセット
            isRunning = false;
        }
    }

    private void saveLastExecutionTime(LocalDate date) {
        try {
            Files.writeString(LAST_EXECUTION_FILE, date.format(DATE_FORMATTER));
        } catch (IOException e) {
            logger.error("Failed to save last execution time: " + e.getMessage());
        }
    }

    private LocalDate loadLastExecutionTime() {
        if (!Files.exists(LAST_EXECUTION_FILE)) {
            return null;
        }
        try {
            String content = Files.readString(LAST_EXECUTION_FILE);
            return LocalDate.parse(content, DATE_FORMATTER);
        } catch (IOException e) {
            logger.error("Failed to load last execution time: " + e.getMessage());
            return null;
        }
    }}