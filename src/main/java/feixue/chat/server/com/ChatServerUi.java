package feixue.chat.server.com;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

// 服务器界面功能：主窗口构建、各功能页签面板与界面交互逻辑
class ChatServerUi {
    final ChatServer server;

    JTextArea logArea;       // 聊天记录显示区域
    JTextField portField;    // 端口输入框
    JButton startBtn;        // 启动服务器按钮
    JButton stopBtn;         // 关闭服务器按钮
    JTextField serverInputField; // 服务器输入框
    JTree onlineUsersTree;
    DefaultMutableTreeNode onlineUsersRoot;
    DefaultTreeModel onlineUsersTreeModel;
    JLabel onlineUsersSummaryLabel;
    JButton privateChatButton;
    JButton privateVoiceButton;
    DefaultListModel<String> channelManagementListModel;
    JList<String> channelManagementList;
    JTextField channelNameField;
    JPasswordField channelPasswordField;
    JCheckBox noChannelPasswordCheckBox;
    JButton addChannelButton;
    JButton deleteChannelButton;
    JButton changeChannelPasswordButton;
    JLabel channelManagementStatusLabel;
    JTextField webVerificationQuestionField;
    JPasswordField webVerificationAnswerField;
    JButton webStartButton;
    JButton webStopButton;
    JButton webSaveButton;
    JLabel webStatusLabel;
    JLabel webClientCountLabel;
    JTextField webPanRootField;
    JCheckBox webPanEnabledCheckBox;
    JLabel webPanStatusLabel;
    JLabel sslStatusLabel;
    JTextField minimumClientVersionField;
    JButton minimumClientVersionSaveButton;
    JLabel minimumClientVersionStatusLabel;
    JPanel voiceChannelsPanel;
    JLabel voiceOverviewLabel;
    final Map<String, VoiceChannelRow> voiceChannelRows = new LinkedHashMap<>();

    ChatServerUi(ChatServer server) {
        this.server = server;
    }

    void buildUi() {
        // 布局设置
        server.setLayout(new BorderLayout());
        server.setTitle("聊天服务器");
        server.setSize(760, 520);
        server.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        server.setLocationRelativeTo(null);

        // 顶部控制栏
        JPanel topPanel = new JPanel();
        portField = new JTextField("22233", 8);
        startBtn = new JButton("启动服务器");
        stopBtn = new JButton("关闭服务器");
        stopBtn.setEnabled(false); // 初始状态禁用
        topPanel.add(new JLabel("端口:"));
        topPanel.add(portField);
        topPanel.add(startBtn);
        topPanel.add(stopBtn);
        server.add(topPanel, BorderLayout.NORTH);

        // 中间日志区域
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(logArea);
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.add(scrollPane, BorderLayout.CENTER);

        // 底部输入栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        serverInputField = new JTextField();
        bottomPanel.add(serverInputField, BorderLayout.CENTER);
        chatPanel.add(bottomPanel, BorderLayout.SOUTH);

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.addTab("服务器日志", chatPanel);
        mainTabs.addTab("在线用户", createOnlineUsersPanel());
        mainTabs.addTab("语音频道", createVoiceChannelsPanel());
        mainTabs.addTab("频道管理", createChannelManagementPanel());
        mainTabs.addTab("网页端", createWebAccessPanel());
        mainTabs.addTab("肥雪网盘", createWebPanPanel());
        mainTabs.addTab("版本限制", createMinimumVersionPanel());
        server.add(mainTabs, BorderLayout.CENTER);

        // 按钮事件
        startBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                server.startServer();
            }
        });
        
        stopBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                server.stopServer();
            }
        });

        // 服务器输入框事件
        serverInputField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleServerInput();
            }
        });
    }

    JPanel createWebAccessPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel settings = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        settings.add(new JLabel("验证问题："), gbc);
        webVerificationQuestionField = new JTextField(server.webVerificationQuestion, 24);
        gbc.gridx = 1;
        gbc.weightx = 1;
        settings.add(webVerificationQuestionField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        settings.add(new JLabel("验证答案："), gbc);
        webVerificationAnswerField = new JPasswordField(server.webVerificationAnswer, 24);
        gbc.gridx = 1;
        gbc.weightx = 1;
        settings.add(webVerificationAnswerField, gbc);

        webSaveButton = new JButton("保存验证设置");
        webSaveButton.addActionListener(e -> saveWebSettingsFromUi(true));
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        settings.add(webSaveButton, gbc);
        panel.add(settings, BorderLayout.NORTH);

        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
        webStatusLabel = new JLabel();
        webStatusLabel.setFont(webStatusLabel.getFont().deriveFont(Font.BOLD, 16f));
        webClientCountLabel = new JLabel();
        webStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        webClientCountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(webStatusLabel);
        statusPanel.add(Box.createVerticalStrut(8));
        statusPanel.add(webClientCountLabel);
        panel.add(statusPanel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        webStartButton = new JButton("启动网页端");
        webStopButton = new JButton("关闭网页端");
        webStartButton.addActionListener(e -> enableWebAccess());
        webStopButton.addActionListener(e -> disableWebAccess(true));
        actions.add(webStartButton);
        actions.add(webStopButton);
        panel.add(actions, BorderLayout.SOUTH);

        refreshWebControlState();
        return panel;
    }

    JPanel createWebPanPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel settings = new JPanel(new BorderLayout(8, 8));
        webPanRootField = new JTextField(server.webPan.getRoot().toString(), 28);
        JPanel directory = new JPanel(new BorderLayout(8, 0));
        directory.add(new JLabel("共享目录："), BorderLayout.WEST);
        directory.add(webPanRootField, BorderLayout.CENTER);
        JButton browse = new JButton("选择目录");
        browse.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(webPanRootField.getText());
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(server) == javax.swing.JFileChooser.APPROVE_OPTION) {
                webPanRootField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        directory.add(browse, BorderLayout.EAST);
        settings.add(directory, BorderLayout.NORTH);
        webPanEnabledCheckBox = new JCheckBox("启用肥雪网盘", server.webPan.isEnabled());
        settings.add(webPanEnabledCheckBox, BorderLayout.CENTER);
        JButton save = new JButton("保存并应用");
        save.addActionListener(e -> {
            try {
                server.webPan.configure(webPanRootField.getText(), webPanEnabledCheckBox.isSelected());
                webPanRootField.setText(server.webPan.getRoot().toString());
                server.log("肥雪网盘设置已保存：" + (server.webPan.isEnabled() ? "启用" : "关闭"));
                refreshWebControlState();
            } catch (IOException | RuntimeException error) {
                JOptionPane.showMessageDialog(server, "保存失败：" + error.getMessage(), "肥雪网盘", JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(save);
        panel.add(settings, BorderLayout.NORTH);
        JPanel status = new JPanel(new GridLayout(0, 1, 0, 8));
        webPanStatusLabel = new JLabel();
        status.add(webPanStatusLabel);
        status.add(new JLabel("访问地址：https://服务器地址:" + server.sslPort + "/webpan/"));
        status.add(new JLabel("共享目录仅限网页验证通过后只读访问；关闭网页端将停止网盘服务。"));
        JPanel center = new JPanel(new BorderLayout());
        center.add(status, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        refreshWebControlState();
        return panel;
    }

    boolean saveWebSettingsFromUi(boolean showConfirmation) {
        String question = webVerificationQuestionField.getText().trim();
        String answer = new String(webVerificationAnswerField.getPassword()).trim();
        if (question.isEmpty() || answer.isEmpty()) {
            JOptionPane.showMessageDialog(server, "验证问题和答案不能为空", "网页端设置",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (question.length() > 100 || answer.length() > 100) {
            JOptionPane.showMessageDialog(server, "验证问题和答案不能超过100个字符", "网页端设置",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            server.config.saveWebConfiguration(question, answer);
            server.webVerificationQuestion = question;
            server.webVerificationAnswer = answer;
            server.log("网页端验证设置已保存");
            if (showConfirmation) {
                JOptionPane.showMessageDialog(server, "验证设置已保存", "网页端设置",
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(server, "保存失败: " + e.getMessage(), "网页端设置",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    void enableWebAccess() {
        if (server.serverSocket == null || server.serverSocket.isClosed() || !server.isRunning) {
            JOptionPane.showMessageDialog(server, "请先启动主服务器", "网页端", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (server.sslServerSocket == null || server.sslServerSocket.isClosed()) {
            JOptionPane.showMessageDialog(server,
                    "HTTPS/WSS 未启动，请检查 ssl-config.json、证书私钥和端口 " + server.sslPort,
                    "网页端", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!saveWebSettingsFromUi(false)) {
            return;
        }
        server.webAccessEnabled = true;
        server.webPan.setWebEnabled(true);
        server.log("网页端已启动：HTTPS/WSS 端口 " + server.sslPort + "；旧客户端 TCP 端口 "
                + portField.getText().trim());
        refreshWebControlState();
    }

    void disableWebAccess(boolean userInitiated) {
        boolean wasEnabled = server.webAccessEnabled;
        server.webAccessEnabled = false;
        server.webPan.setWebEnabled(false);
        List<ClientHandler> clients = new ArrayList<>(server.webClientHandlers);
        for (ClientHandler client : clients) {
            try {
                client.sendMessage("/web_shutdown|网页端已关闭");
                client.closeConnection();
            } catch (IOException ignored) {
            }
        }
        if (wasEnabled && userInitiated) {
            server.log("网页端已关闭，已断开 " + clients.size() + " 个网页会话");
        }
        refreshWebControlState();
    }

    void refreshWebControlState() {
        if (webStatusLabel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            boolean serverOnline = server.serverSocket != null && !server.serverSocket.isClosed() && server.isRunning;
            String sslState = server.sslServerSocket != null && !server.sslServerSocket.isClosed()
                    ? "HTTPS/WSS 端口 " + server.sslPort
                    : "HTTPS/WSS 未启动";
            webStatusLabel.setText(server.webAccessEnabled
                    ? "运行状态：已启动（" + sslState + "；客户端 TCP 端口 "
                    + portField.getText().trim() + "）"
                    : "运行状态：已关闭（网页端口：HTTPS/WSS " + server.sslPort + "）");
            webClientCountLabel.setText("当前网页连接：" + server.webClientHandlers.size());
            webStartButton.setEnabled(serverOnline && !server.webAccessEnabled);
            webStopButton.setEnabled(server.webAccessEnabled);
            if (webPanStatusLabel != null) {
                webPanStatusLabel.setText(!server.webPan.isEnabled() ? "运行状态：已关闭"
                        : !server.webAccessEnabled ? "运行状态：已启用，等待网页端启动"
                        : server.webPan.isAvailable() ? "运行状态：服务中" : "运行状态：共享目录不可用");
            }
        });
    }

    JPanel createMinimumVersionPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel editor = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        editor.add(new JLabel("最低版本通过号："), gbc);

        minimumClientVersionField = new JTextField(server.minimumClientVersion, 20);
        gbc.gridx = 1;
        gbc.weightx = 1;
        editor.add(minimumClientVersionField, gbc);

        minimumClientVersionSaveButton = new JButton("保存");
        minimumClientVersionSaveButton.addActionListener(e -> saveMinimumVersionFromUi());
        gbc.gridx = 2;
        gbc.weightx = 0;
        editor.add(minimumClientVersionSaveButton, gbc);
        panel.add(editor, BorderLayout.NORTH);

        minimumClientVersionStatusLabel = new JLabel();
        minimumClientVersionStatusLabel.setFont(
                minimumClientVersionStatusLabel.getFont().deriveFont(Font.BOLD, 15f));
        panel.add(minimumClientVersionStatusLabel, BorderLayout.CENTER);

        refreshMinimumVersionControlState();
        return panel;
    }

    void saveMinimumVersionFromUi() {
        if (!isMinimumVersionSettingsAllowed()) {
            JOptionPane.showMessageDialog(server, "只能在服务器关闭时修改最低版本", "版本限制",
                    JOptionPane.WARNING_MESSAGE);
            refreshMinimumVersionControlState();
            return;
        }
        String version = minimumClientVersionField.getText().trim();
        if (!server.messageGuard.isValidVersionNumber(version)) {
            JOptionPane.showMessageDialog(server, "版本号只能包含数字和点，例如 3.0.1", "版本限制",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            server.config.saveMinimumVersionConfiguration(version);
            server.minimumClientVersion = version;
            minimumClientVersionField.setText(version);
            refreshMinimumVersionControlState();
            server.log("最低客户端版本已更新为: " + version);
            JOptionPane.showMessageDialog(server, "最低版本已保存", "版本限制",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(server, "保存失败: " + e.getMessage(), "版本限制",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    boolean isMinimumVersionSettingsAllowed() {
        return !server.serverStarting && (server.serverSocket == null || server.serverSocket.isClosed());
    }

    void refreshMinimumVersionControlState() {
        if (minimumClientVersionField == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            boolean editable = isMinimumVersionSettingsAllowed();
            minimumClientVersionField.setEnabled(editable);
            minimumClientVersionSaveButton.setEnabled(editable);
            minimumClientVersionStatusLabel.setText(editable
                    ? "当前最低通过版本：" + server.minimumClientVersion + "（服务器已关闭）"
                    : "当前最低通过版本：" + server.minimumClientVersion + "（服务器运行中，设置已锁定）");
        });
    }

    JPanel createChannelManagementPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel editor = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        editor.add(new JLabel("频道："), gbc);
        channelNameField = new JTextField(20);
        gbc.gridx = 1;
        gbc.weightx = 1;
        editor.add(channelNameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        editor.add(new JLabel("密码："), gbc);
        channelPasswordField = new JPasswordField(20);
        gbc.gridx = 1;
        gbc.weightx = 1;
        editor.add(channelPasswordField, gbc);

        noChannelPasswordCheckBox = new JCheckBox("无密码");
        noChannelPasswordCheckBox.addActionListener(e -> refreshChannelManagementState());
        gbc.gridx = 1;
        gbc.gridy = 2;
        editor.add(noChannelPasswordCheckBox, gbc);

        addChannelButton = new JButton("添加");
        addChannelButton.addActionListener(e -> addConfiguredChannel());
        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        editor.add(addChannelButton, gbc);
        panel.add(editor, BorderLayout.NORTH);

        channelManagementListModel = new DefaultListModel<>();
        channelManagementList = new JList<>(channelManagementListModel);
        channelManagementList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        channelManagementList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedChannelIntoEditor();
                refreshChannelManagementState();
            }
        });
        panel.add(new JScrollPane(channelManagementList), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        channelManagementStatusLabel = new JLabel();
        bottom.add(channelManagementStatusLabel, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        changeChannelPasswordButton = new JButton("修改密码");
        deleteChannelButton = new JButton("删除频道");
        changeChannelPasswordButton.addActionListener(e -> changeConfiguredChannelPassword());
        deleteChannelButton.addActionListener(e -> deleteConfiguredChannel());
        actions.add(changeChannelPasswordButton);
        actions.add(deleteChannelButton);
        bottom.add(actions, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);

        refreshChannelManagementPanel();
        return panel;
    }

    void loadSelectedChannelIntoEditor() {
        String selected = channelManagementList == null ? null : channelManagementList.getSelectedValue();
        if (selected == null || ChatServer.PUBLIC_CHANNEL_DISPLAY.equals(selected)) {
            return;
        }
        channelNameField.setText(selected);
        channelPasswordField.setText("");
        noChannelPasswordCheckBox.setSelected(server.accountPasswords.getOrDefault(selected, "").isEmpty());
    }

    void refreshChannelManagementPanel() {
        if (channelManagementListModel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            String selected = channelManagementList.getSelectedValue();
            channelManagementListModel.clear();
            channelManagementListModel.addElement(ChatServer.PUBLIC_CHANNEL_DISPLAY);
            for (String channel : server.accountPasswords.keySet()) {
                channelManagementListModel.addElement(channel);
            }
            if (selected != null) {
                channelManagementList.setSelectedValue(selected, true);
            }
            refreshChannelManagementState();
        });
    }

    void refreshChannelManagementState() {
        if (addChannelButton == null) {
            return;
        }
        boolean editable = isChannelManagementAllowed();
        String selected = channelManagementList == null ? null : channelManagementList.getSelectedValue();
        boolean privateChannelSelected = selected != null && !ChatServer.PUBLIC_CHANNEL_DISPLAY.equals(selected);
        channelNameField.setEnabled(editable);
        noChannelPasswordCheckBox.setEnabled(editable);
        channelPasswordField.setEnabled(editable && !noChannelPasswordCheckBox.isSelected());
        addChannelButton.setEnabled(editable);
        deleteChannelButton.setEnabled(editable && privateChannelSelected);
        changeChannelPasswordButton.setEnabled(editable && privateChannelSelected);
        channelManagementStatusLabel.setText(editable
                ? "服务器已关闭，可以管理频道"
                : "服务器运行中，频道管理已锁定");
    }

    boolean isChannelManagementAllowed() {
        return !server.serverStarting && (server.serverSocket == null || server.serverSocket.isClosed());
    }

    void addConfiguredChannel() {
        if (!ensureChannelManagementAllowed()) {
            return;
        }
        String name = channelNameField.getText().trim();
        String password = noChannelPasswordCheckBox.isSelected()
                ? "" : new String(channelPasswordField.getPassword());
        String validationError = validateChannelInput(name, password, noChannelPasswordCheckBox.isSelected());
        if (validationError != null) {
            JOptionPane.showMessageDialog(server, validationError, "无法添加频道", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(server.accountPasswords);
        if (updated.containsKey(name)) {
            JOptionPane.showMessageDialog(server, "频道已经存在", "无法添加频道", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String newGroup = server.config.channelGroupForName(name);
        for (String existingName : updated.keySet()) {
            if (server.config.channelGroupForName(existingName).equals(newGroup)) {
                JOptionPane.showMessageDialog(server, "频道内部名称与 " + existingName + " 冲突",
                        "无法添加频道", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        updated.put(name, password);
        if (writeChannelConfiguration(updated, "频道已添加: " + name)) {
            channelNameField.setText("");
            channelPasswordField.setText("");
            noChannelPasswordCheckBox.setSelected(false);
        }
    }

    void deleteConfiguredChannel() {
        if (!ensureChannelManagementAllowed()) {
            return;
        }
        String selected = channelManagementList.getSelectedValue();
        if (selected == null || ChatServer.PUBLIC_CHANNEL_DISPLAY.equals(selected)) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(server, "确定删除频道 " + selected + " 吗？",
                "删除频道", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(server.accountPasswords);
        updated.remove(selected);
        writeChannelConfiguration(updated, "频道已删除: " + selected);
    }

    void changeConfiguredChannelPassword() {
        if (!ensureChannelManagementAllowed()) {
            return;
        }
        String selected = channelManagementList.getSelectedValue();
        if (selected == null || ChatServer.PUBLIC_CHANNEL_DISPLAY.equals(selected)) {
            return;
        }
        String password = noChannelPasswordCheckBox.isSelected()
                ? "" : new String(channelPasswordField.getPassword());
        String validationError = validateChannelInput(selected, password, noChannelPasswordCheckBox.isSelected());
        if (validationError != null) {
            JOptionPane.showMessageDialog(server, validationError, "无法修改密码", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Map<String, String> updated = new LinkedHashMap<>(server.accountPasswords);
        updated.put(selected, password);
        if (writeChannelConfiguration(updated, "频道密码已修改: " + selected)) {
            channelPasswordField.setText("");
            noChannelPasswordCheckBox.setSelected(false);
        }
    }

    boolean ensureChannelManagementAllowed() {
        if (isChannelManagementAllowed()) {
            return true;
        }
        JOptionPane.showMessageDialog(server, "请先关闭服务器，再管理频道", "频道管理已锁定",
                JOptionPane.WARNING_MESSAGE);
        refreshChannelManagementState();
        return false;
    }

    String validateChannelInput(String name, String password, boolean noPassword) {
        if (name.isEmpty()) {
            return "请输入频道名";
        }
        if ("public".equalsIgnoreCase(name) || "公共".equals(name)
                || ChatServer.PUBLIC_CHANNEL_GROUP.equalsIgnoreCase(name)) {
            return "公开频道是内置频道，不能重复创建";
        }
        if (name.indexOf('|') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                || name.indexOf('{') >= 0 || name.indexOf('}') >= 0) {
            return "频道名不能包含 |、换行或大括号";
        }
        if (!noPassword && password.isEmpty()) {
            return "请输入密码，或勾选无密码";
        }
        if (password.indexOf('|') >= 0 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0
                || password.indexOf('{') >= 0 || password.indexOf('}') >= 0) {
            return "密码不能包含 |、换行或大括号";
        }
        return null;
    }

    boolean writeChannelConfiguration(Map<String, String> updated, String successMessage) {
        try {
            server.config.saveOnlyPdConfiguration(updated);
            server.log(successMessage);
            refreshChannelManagementPanel();
            return true;
        } catch (IOException e) {
            server.log("写入 " + ChatServer.ONLY_PD_CONFIG_FILE + " 失败: " + e.getMessage());
            JOptionPane.showMessageDialog(server, "配置文件写入失败: " + e.getMessage(),
                    "频道管理失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    JPanel createOnlineUsersPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        onlineUsersSummaryLabel = new JLabel("在线用户 0 个");
        onlineUsersSummaryLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 3, 8));
        panel.add(onlineUsersSummaryLabel, BorderLayout.NORTH);

        onlineUsersRoot = new DefaultMutableTreeNode("频道");
        onlineUsersTreeModel = new DefaultTreeModel(onlineUsersRoot);
        onlineUsersTree = new JTree(onlineUsersTreeModel);
        onlineUsersTree.setRootVisible(false);
        onlineUsersTree.addTreeSelectionListener(e -> refreshOnlineUserActionState());
        onlineUsersTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String username = getSelectedOnlineUsername();
                    if (username != null) {
                        promptServerPrivateChat(username);
                    }
                }
            }
        });
        panel.add(new JScrollPane(onlineUsersTree), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        privateChatButton = new JButton("私聊");
        privateVoiceButton = new JButton("语音通话");
        privateChatButton.addActionListener(e -> {
            String username = getSelectedOnlineUsername();
            if (username != null) {
                promptServerPrivateChat(username);
            }
        });
        privateVoiceButton.addActionListener(e -> {
            String username = getSelectedOnlineUsername();
            if (username != null) {
                server.voiceManager.requestServerP2PVoice(username);
            }
        });
        actions.add(privateChatButton);
        actions.add(privateVoiceButton);
        panel.add(actions, BorderLayout.SOUTH);

        refreshOnlineUserActionState();
        refreshOnlineUsersPanel();
        return panel;
    }

    void refreshOnlineUserActionState() {
        if (privateChatButton == null || privateVoiceButton == null) {
            return;
        }
        boolean hasUser = getSelectedOnlineUsername() != null;
        privateChatButton.setEnabled(hasUser);
        privateVoiceButton.setEnabled(hasUser);
    }

    String getSelectedOnlineUsername() {
        if (onlineUsersTree == null) {
            return null;
        }
        TreePath path = onlineUsersTree.getSelectionPath();
        if (path == null || path.getPathCount() < 3) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode)) {
            return null;
        }
        Object userObject = ((DefaultMutableTreeNode) last).getUserObject();
        return userObject == null ? null : userObject.toString();
    }

    void refreshOnlineUsersPanel() {
        if (onlineUsersRoot == null || onlineUsersTreeModel == null) {
            return;
        }
        Map<String, List<String>> snapshot = getOnlineUsersByGroupSnapshot();
        SwingUtilities.invokeLater(() -> {
            onlineUsersRoot.removeAllChildren();
            int total = 0;
            for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
                DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(
                        entry.getKey() + " (" + entry.getValue().size() + ")");
                for (String username : entry.getValue()) {
                    groupNode.add(new DefaultMutableTreeNode(username));
                    total++;
                }
                onlineUsersRoot.add(groupNode);
            }
            onlineUsersTreeModel.reload();
            for (int i = 0; i < onlineUsersTree.getRowCount(); i++) {
                onlineUsersTree.expandRow(i);
            }
            if (onlineUsersSummaryLabel != null) {
                onlineUsersSummaryLabel.setText("在线用户 " + total + " 个 | 频道 "
                        + snapshot.size() + " 个");
            }
            refreshOnlineUserActionState();
        });
    }

    Map<String, List<String>> getOnlineUsersByGroupSnapshot() {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (String channel : server.voiceManager.knownVoiceChannels()) {
            grouped.put(channel, new TreeSet<>());
        }
        for (Map.Entry<String, List<ClientHandler>> entry : server.groups.entrySet()) {
            Set<String> activeUsers = new TreeSet<>();
            for (ClientHandler client : new ArrayList<>(entry.getValue())) {
                String nickname = client.getNickname();
                if (nickname != null && server.onlineUsers.contains(nickname)) {
                    activeUsers.add(nickname);
                }
            }
            if (!activeUsers.isEmpty()) {
                grouped.computeIfAbsent(entry.getKey(), key -> new TreeSet<>()).addAll(activeUsers);
            }
        }

        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    JPanel createVoiceChannelsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        voiceOverviewLabel = new JLabel("语音频道服务未启动");
        voiceOverviewLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 3, 8));
        panel.add(voiceOverviewLabel, BorderLayout.NORTH);
        voiceChannelsPanel = new JPanel();
        voiceChannelsPanel.setLayout(new BoxLayout(voiceChannelsPanel, BoxLayout.Y_AXIS));
        panel.add(new JScrollPane(voiceChannelsPanel), BorderLayout.CENTER);
        server.voiceManager.refreshVoiceChannelPanel();
        return panel;
    }

    void promptServerPrivateChat(String username) {
        ClientHandler target = server.userManager.findClientHandlerByNickname(username);
        if (target == null) {
            server.log("服务器私聊失败，用户不在线: " + username);
            refreshOnlineUsersPanel();
            return;
        }
        String message = JOptionPane.showInputDialog(server,
                "发送给 " + username + " 的私聊消息:",
                "服务器私聊", JOptionPane.PLAIN_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        server.console.sendServerPrivateMessage(username, message.trim());
    }

    // 处理服务器输入
    void handleServerInput() {
        String input = serverInputField.getText().trim();
        if (input.isEmpty()) return;

        serverInputField.setText("");

        if (input.startsWith("/")) {
            // 处理命令
            server.console.handleServerCommand(input);
        } else {
            // 普通消息
            server.log("[server] " + input);
            // 广播到所有群组
            server.console.broadcastFromServer(input);
        }
    }
}
