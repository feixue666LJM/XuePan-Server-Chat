package feixue.chat.server.com;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.*;

// 语音频道管理界面中的单行控件（每个频道一行）
class VoiceChannelRow {
    final JPanel panel;
    final JLabel statusLabel;
    final JToggleButton enabledButton;
    final JButton joinButton;
    final JComboBox<String> volumeBox;

    VoiceChannelRow(String group) {
        panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        JLabel nameLabel = new JLabel(group);
        statusLabel = new JLabel();
        JPanel info = new JPanel(new GridLayout(2, 1));
        info.add(nameLabel);
        info.add(statusLabel);
        enabledButton = new JToggleButton("开启");
        joinButton = new JButton("服务器加入");
        volumeBox = new JComboBox<>(new String[]{"x1", "x2", "x3", "x4", "x5", "x6"});
        volumeBox.setToolTipText("设置该频道实时语音的播放音量倍率");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(new JLabel("音量"));
        actions.add(volumeBox);
        actions.add(enabledButton);
        actions.add(joinButton);
        panel.add(info, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.EAST);
    }
}
