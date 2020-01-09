package org.code4everything.wetool.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Pair;
import cn.hutool.core.swing.clipboard.ClipboardUtil;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.code4everything.boot.base.FileUtils;
import org.code4everything.wetool.WeApplication;
import org.code4everything.wetool.constant.FileConsts;
import org.code4everything.wetool.constant.TipConsts;
import org.code4everything.wetool.constant.TitleConsts;
import org.code4everything.wetool.constant.ViewConsts;
import org.code4everything.wetool.plugin.PluginLoader;
import org.code4everything.wetool.plugin.support.BaseViewController;
import org.code4everything.wetool.plugin.support.config.WeConfig;
import org.code4everything.wetool.plugin.support.config.WeStart;
import org.code4everything.wetool.plugin.support.constant.AppConsts;
import org.code4everything.wetool.plugin.support.factory.BeanFactory;
import org.code4everything.wetool.plugin.support.util.FxDialogs;
import org.code4everything.wetool.plugin.support.util.FxUtils;
import org.code4everything.wetool.plugin.support.util.WeUtils;
import org.code4everything.wetool.util.FinalUtils;

import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @author pantao
 * @since 2018/3/30
 */
@Slf4j
public class MainController {

    private final ThreadFactory FACTORY = ThreadFactoryBuilder.create().setDaemon(true).build();

    private final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(1, FACTORY);

    private final Map<String, Pair<String, String>> TAB_MAP = new HashMap<>(16);

    private final WeConfig config = WeUtils.getConfig();

    @FXML
    public TabPane tabPane;

    @FXML
    public Menu fileMenu;

    @FXML
    public Menu toolMenu;

    @FXML
    public Menu pluginMenu;

    {
        TAB_MAP.put("FileManager", new Pair<>(TitleConsts.FILE_MANAGER, ViewConsts.FILE_MANAGER));
        TAB_MAP.put("JsonParser", new Pair<>(TitleConsts.JSON_PARSER, ViewConsts.JSON_PARSER));
        TAB_MAP.put("RandomGenerator", new Pair<>(TitleConsts.RANDOM_GENERATOR, ViewConsts.RANDOM_GENERATOR));
        TAB_MAP.put("ClipboardHistory", new Pair<>(TitleConsts.CLIPBOARD_HISTORY, ViewConsts.CLIPBOARD_HISTORY));
        TAB_MAP.put("QrCodeGenerator", new Pair<>(TitleConsts.QR_CODE_GENERATOR, ViewConsts.QR_CODE_GENERATOR));
        TAB_MAP.put("CharsetConverter", new Pair<>(TitleConsts.CHARSET_CONVERTER, ViewConsts.CHARSET_CONVERTER));
        TAB_MAP.put("NetworkTool", new Pair<>(TitleConsts.NETWORK_TOOL, ViewConsts.NETWORK_TOOL));
        TAB_MAP.put("NaryConverter", new Pair<>(TitleConsts.NARY_CONVERTER, ViewConsts.NARY_CONVERTER));
    }

    /**
     * 此对象暂时不注册到工厂
     */
    @FXML
    private void initialize() {
        BeanFactory.register(tabPane);
        BeanFactory.register(AppConsts.BeanKey.PLUGIN_MENU, pluginMenu);
        // 加载插件
        PluginLoader.loadPlugins();

        // 加载快速启动选项
        Set<WeStart> starts = WeUtils.getConfig().getQuickStarts();
        if (CollUtil.isNotEmpty(starts)) {
            Menu menu = new Menu(TitleConsts.QUICK_START);
            setQuickStartMenu(menu, starts);
            fileMenu.getItems().add(0, new SeparatorMenuItem());
            fileMenu.getItems().add(0, menu);
        }
        // 加载工具选项卡
        loadToolMenus(toolMenu);
        // 加载默认选项卡
        loadTabs();
        // 监听剪贴板
        config.appendClipboardHistory(new Date(), ClipboardUtil.getStr());
        EXECUTOR.scheduleWithFixedDelay(this::watchClipboard, 0, 1000, TimeUnit.MILLISECONDS);
    }

    public void loadPluginsHandy() {
        FxUtils.chooseFiles(files -> PluginLoader.loadPlugins(files, false));
    }

    private void loadToolMenus(Menu menu) {
        menu.getItems().forEach(item -> {
            if (item instanceof Menu) {
                loadToolMenus((Menu) item);
            } else if (StrUtil.isNotEmpty(item.getId())) {
                item.setOnAction(e -> openTab(TAB_MAP.get(item.getId())));
            }
        });
    }

    private void setQuickStartMenu(Menu menu, Set<WeStart> starts) {
        starts.forEach(start -> {
            if (CollUtil.isEmpty(start.getSubStarts())) {
                // 添加子菜单
                MenuItem item = new MenuItem(start.getAlias());
                item.setOnAction(e -> FxUtils.openFile(start.getLocation()));
                menu.getItems().add(item);
            } else {
                // 添加父级菜单
                Menu subMenu = new Menu(start.getAlias());
                menu.getItems().add(subMenu);
                setQuickStartMenu(subMenu, start.getSubStarts());
            }
        });
    }

    private void watchClipboard() {
        String clipboard;
        String last;
        try {
            // 忽略系统休眠时抛出的异常
            clipboard = ClipboardUtil.getStr();
            last = config.getLastClipboardHistoryItem().getValue();
        } catch (Exception e) {
            WeUtils.printDebug(e.getMessage());
            clipboard = last = "";
        }
        if (StrUtil.isEmpty(clipboard) || last.equals(clipboard)) {
            return;
        }
        // 剪贴板发生变化
        String compress = WeUtils.compressString(clipboard);
        log.info("clipboard changed: {}", compress);
        Date date = new Date();
        config.appendClipboardHistory(date, clipboard);
        ClipboardHistoryController controller = FinalUtils.getView(TitleConsts.CLIPBOARD_HISTORY);
        if (ObjectUtil.isNotNull(controller)) {
            // 显示到文本框
            final String clip = clipboard;
            Platform.runLater(() -> controller.insert(date, clip));
        }
    }

    private void loadTabs() {
        for (String tabName : config.getInitialize().getTabs().getLoads()) {
            openTab(TAB_MAP.get(tabName));
        }
    }

    private void openTab(Pair<String, String> tabPair) {
        if (Objects.isNull(tabPair)) {
            return;
        }
        Pane box = FxUtils.loadFxml(WeApplication.class, tabPair.getValue(), false);
        if (Objects.isNull(box)) {
            return;
        }
        // 打开选项卡
        FinalUtils.openTab(box, tabPair.getKey());
    }

    public void openFile() {
        FxUtils.chooseFile(file -> {
            BaseViewController controller = FxUtils.getSelectedTabController();
            if (ObjectUtil.isNotNull(controller)) {
                controller.openFile(file);
            }
        });
    }

    public void saveFile() {
        FxUtils.saveFile(file -> {
            BaseViewController controller = FxUtils.getSelectedTabController();
            if (ObjectUtil.isNotNull(controller)) {
                controller.saveFile(file);
            }
        });
    }

    public void openMultiFile() {
        FxUtils.chooseFiles(files -> {
            BaseViewController controller = FxUtils.getSelectedTabController();
            if (ObjectUtil.isNotNull(controller)) {
                controller.openMultiFiles(files);
            }
        });
    }

    public void openFolder() {
        FxUtils.chooseFolder(folder -> {
            BaseViewController controller = FxUtils.getSelectedTabController();
            if (ObjectUtil.isNotNull(controller)) {
                controller.openFolder(folder);
            }
        });
    }

    public void quit() {
        WeUtils.exitSystem();
    }

    public void about() {
        FxDialogs.showInformation(TitleConsts.ABOUT_APP, TipConsts.ABOUT_APP);
    }

    public void closeAllTab() {
        tabPane.getTabs().clear();
    }

    public void openAllTab() {
        TAB_MAP.forEach((k, v) -> openTab(v));
    }

    public void openLog() {
        FxUtils.openFile(FileConsts.LOG);
    }

    public void restart() {
        FxUtils.restart();
    }

    public void openConfig() {
        FinalUtils.openConfig();
    }

    public void seePluginRepo() {
        FxUtils.openLink(TipConsts.REPO_LINK);
    }

    public void openPluginFolder() {
        FinalUtils.openPluginFolder();
    }

    public void openLogFolder() {
        FxUtils.openFile(FileConsts.LOG_FOLDER);
    }

    public void openWorkFolder() {
        FxUtils.openFile(FileUtils.currentWorkDir());
    }

    public void seeJavaInfo() {
        TextArea area = new TextArea(getAllJavaInfos());
        VBox.setVgrow(area, Priority.ALWAYS);
        VBox box = new VBox(area);
        box.setPrefWidth(600);
        box.setPrefHeight(700);
        FxDialogs.showDialog(null, box);
    }

    public void clearAllCache() {
        tabPane.getTabs().clear();
        BeanFactory.clearCache();
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    private String getAllJavaInfos() {
        StringBuilder builder = new StringBuilder();
        builder.append("JavaVirtualMachineSpecification信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getJvmSpecInfo());

        builder.append("\r\nJavaVirtualMachineImplementation信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getJvmInfo());

        builder.append("\r\nJavaSpecification信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getJavaSpecInfo());

        builder.append("\r\nJavaImplementation信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getJavaInfo());

        builder.append("\r\nJava运行时信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getJavaRuntimeInfo());

        builder.append("\r\n系统信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getOsInfo());

        builder.append("\r\n用户信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getUserInfo());

        builder.append("\r\n当前主机网络地址信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getHostInfo());

        builder.append("\r\n运行时信息：\r\n");
        builder.append("=========================================================================================\r\n");
        builder.append(SystemUtil.getRuntimeInfo());

        return builder.toString();
    }
}
