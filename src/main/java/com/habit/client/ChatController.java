package com.habit.client;

import com.habit.domain.util.Config;
import com.habit.domain.Message;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.json.*;

public class ChatController {
  /* チーム名ラベル */
  @FXML private Label teamNameLabel;
  /* チャットリスト */
  @FXML private ListView<String> chatList;
  /* チャット入力フィールド */
  @FXML private TextField chatInput;
  /* チャット送信ボタン */
  @FXML private Button btnSend;
  /* チームトップに戻るボタン */
  @FXML private Button btnBackToTeamTop;

  private final String serverUrl = Config.getServerUrl() +
     "/sendChatMessage";
  private final String chatLogUrl = Config.getServerUrl() +
     "/getChatLog";

  // 遷移時に渡すデータとセッター
  private String userId;
  private String teamID;
  private String teamName = "チーム名未取得";

  public void setUserId(String userId) { this.userId = userId; }

  public void setTeamID(String teamID) {
    this.teamID = teamID;
    fetchAndSetTeamName(teamID);
    loadChatLog(); // teamIDがセットされた後に履歴を取得
  }

  public void setTeamName(String teamName) {
    this.teamName = teamName;
    if (teamNameLabel != null) {
      teamNameLabel.setText(teamName);
    }
  }

  /**
   * チームIDに基づいてサーバーからチーム名を取得し、ラベルに設定するメソッド。
   * チームIDがセットされたタイミングで呼び出される。
   */
  private void fetchAndSetTeamName(String teamID) {
    new Thread(() -> {
      try {
        HttpClient client = HttpClient.newHttpClient();
        String urlStr = Config.getServerUrl() + "/getTeamName?teamID=" +
                        URLEncoder.encode(teamID, "UTF-8");
        HttpRequest request = HttpRequest.newBuilder()
                                  .uri(URI.create(urlStr))
                                  .timeout(java.time.Duration.ofSeconds(3))
                                  .GET()
                                  .build();
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        String name = response.body();
        if (name != null && !name.isEmpty()) {
          javafx.application.Platform.runLater(() -> {
            teamName = name;
            if (teamNameLabel != null) {
              teamNameLabel.setText(teamName);
            }
          });
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }

  /**
   * コントローラー初期化処理。
   * チーム名の設定やボタンのアクション設定を行う。
   */
  @FXML
  public void initialize() {
    // loadChatLog()はここで呼ばない
    // チーム名がセットされている場合はラベルに表示
    if (teamNameLabel != null && teamName != null) {
      teamNameLabel.setText(teamName);
    }

    // チャット送信ボタンのアクション設定
    btnSend.setOnAction(unused -> {
      String msg = chatInput.getText();
      if (msg != null && !msg.isEmpty()) {
        sendChatMessage(msg);
        chatInput.clear();
      }
    });

    // チームトップに戻るボタンのアクション設定
    btnBackToTeamTop.setOnAction(unused -> {
      try {
        javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
            getClass().getResource("/com/habit/client/gui/TeamTop.fxml"));
        javafx.scene.Parent root = loader.load();
        TeamTopController controller = loader.getController();
        controller.setUserId(userId);
        controller.setTeamID(teamID);
        controller.setTeamName(teamName);
        javafx.stage.Stage stage =
            (javafx.stage.Stage)btnBackToTeamTop.getScene().getWindow();
        stage.setScene(new javafx.scene.Scene(root));
        stage.setTitle("チームトップ");
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    });
  }

  /**
   * チャットログをサーバーから取得し、リストに表示するメソッド。
   * 新しいスレッドで実行される。
   */
  private void loadChatLog() {
    new Thread(() -> {
      try {
        HttpClient client = HttpClient.newHttpClient();
        String url = chatLogUrl +
                     "?teamID=" + URLEncoder.encode(teamID, "UTF-8") +
                     "&limit=50";
        HttpRequest request = HttpRequest.newBuilder()
                                  .uri(URI.create(url))
                                  .timeout(java.time.Duration.ofSeconds(3))
                                  .GET()
                                  .build();
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());

        List<Message> messages = new ArrayList<>();
        JSONArray arr = new JSONArray(response.body());
        for (int i = 0; i < arr.length(); i++) {
          JSONObject obj = arr.getJSONObject(i);
          messages.add(Message.fromJson(obj));
        }

        // sort according to time stamp
        messages.sort(Comparator.comparing(Message::getTimestamp));

        // sort and format messages
        List<String> chatItems = new ArrayList<>();
        // format is [timestamp][usrname]: [content]
        for (var msg : messages) {
          final String formatPattern = "yyyy-MM-dd HH:mm:ss";

          StringBuilder sb = new StringBuilder();
          sb.append('[' +
                    msg.getTimestamp().format(
                        DateTimeFormatter.ofPattern(formatPattern)) +
                    ']');
          sb.append('[' + msg.getSender().getUsername() + ']');
          sb.append(": " + msg.getContent());
          chatItems.add(sb.toString());
        }

        Platform.runLater(() -> { chatList.getItems().setAll(chatItems); });
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }

  /**
   * チャットメッセージをサーバーに送信するメソッド。
   * 新しいスレッドで実行される。
   *
   * @param message 送信するチャットメッセージ
   */
  private void sendChatMessage(String message) {
    new Thread(() -> {
      try {
        HttpClient client = HttpClient.newHttpClient();
        String params = "senderId=" + URLEncoder.encode(userId, "UTF-8") +
                        "&teamID=" + URLEncoder.encode(teamID, "UTF-8") +
                        "&content=" + URLEncoder.encode(message, "UTF-8");
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .timeout(java.time.Duration.ofSeconds(3))
                .header("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(params))
                .build();
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          loadChatLog();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }
}
