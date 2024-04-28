package org.code4everything.wetool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.cron.CronUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.system.SystemUtil;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.dustinredmond.fxtrayicon.FXTrayIcon;
import io.netty.handler.codec.http.HttpResponseStatus;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;
import org.code4everything.boot.base.FileUtils;
import org.code4everything.boot.base.ObjectUtils;
import org.code4everything.boot.base.constant.IntegerConsts;
import org.code4everything.boot.config.BootConfig;
import org.code4everything.wetool.adapter.NativeKeyEventAdapter;
import org.code4everything.wetool.constant.TipConsts;
import org.code4everything.wetool.constant.TitleConsts;
import org.code4everything.wetool.constant.ViewConsts;
import org.code4everything.wetool.handler.MouseLocationListenerEventHandler;
import org.code4everything.wetool.plugin.support.config.WeConfig;
import org.code4everything.wetool.plugin.support.config.WePluginConfig;
import org.code4everything.wetool.plugin.support.config.WeStart;
import org.code4everything.wetool.plugin.support.config.WeStatus;
import org.code4everything.wetool.plugin.support.druid.DruidSource;
import org.code4everything.wetool.plugin.support.event.EventCenter;
import org.code4everything.wetool.plugin.support.event.EventMode;
import org.code4everything.wetool.plugin.support.event.EventPublisher;
import org.code4everything.wetool.plugin.support.event.message.KeyboardListenerEventMessage;
import org.code4everything.wetool.plugin.support.event.message.QuickStartEventMessage;
import org.code4everything.wetool.plugin.support.exception.HttpException;
import org.code4everything.wetool.plugin.support.exception.ToDialogException;
import org.code4everything.wetool.plugin.support.factory.BeanFactory;
import org.code4everything.wetool.plugin.support.http.HttpService;
import org.code4everything.wetool.plugin.support.http.Https;
import org.code4everything.wetool.plugin.support.http.ObjectResp;
import org.code4everything.wetool.plugin.support.listener.WeKeyboardListener;
import org.code4everything.wetool.plugin.support.listener.WeMouseListener;
import org.code4everything.wetool.plugin.support.util.FxDialogs;
import org.code4everything.wetool.plugin.support.util.FxUtils;
import org.code4everything.wetool.plugin.support.util.WeUtils;
import org.code4everything.wetool.service.HttpFileBrowserService;
import org.code4everything.wetool.util.FinalUtils;
import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.keyboard.NativeKeyEvent;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author pantao
 * @since 2018/3/30
 */
@Slf4j
public class WeApplication extends Application {

    private static final Set<String> DEBUG_OPTIONS = Set.of("debug", "-d", "--debug");

    private static final Menu PLUGIN_MENU = new Menu(TitleConsts.PLUGIN);

    private static final ThreadFactory FACTORY = ThreadFactoryBuilder.create().setDaemon(true).setUncaughtExceptionHandler((t, e) -> log.error(ExceptionUtil.stacktraceToString(e, Integer.MAX_VALUE))).build();

    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(2, FACTORY);

    private static Pane rootPane;

    private static Scene rootScene;

    private static Scene getRootScene() {
        return rootScene;
    }

    private static void setRootScene(Scene rootScene) {
        WeApplication.rootScene = rootScene;
    }

    private static Pane getRootPane() {
        return rootPane;
    }

    public static void main(String[] args) {
        log.info("starting wetool on os: {}", SystemUtil.getOsInfo().getName());
        log.info("default charset: {}", Charset.defaultCharset().name());
        BeanFactory.register(new WeStatus().setState(WeStatus.State.STARTING));
        //添加关闭程序钩子
        addShutdownHook();
        boolean debug = BootConfig.isDebug();
        if (ArrayUtil.isNotEmpty(args)) {
            for (String arg : args) {
                if (DEBUG_OPTIONS.contains(arg)) {
                    debug = true;
                    BootConfig.setDebug(true);
                    break;
                }
            }
        }

        parseConfig();
        if (debug) {
            // 因配置文件可能会修改debug mode，所以这里需要确保解析配置文件后debug模式仍然为true
            WeUtils.getConfig().setDebug(true);
            BootConfig.setDebug(true);
        }
        if (BootConfig.isDebug()) {
            log.info("enable debug mode");
        }
        //检查是否已启动
        checkAlreadyRunning();
        //初始化应用
        initApp();
        launch(args);
    }

    private static void addShutdownHook() {
        log.info("register shutdown hook");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            BeanFactory.get(WeStatus.class).setState(WeStatus.State.TERMINATING);
            EventCenter.publishEvent(EventCenter.EVENT_WETOOL_EXIT, DateUtil.date());
            log.info("close druid data source connections");
            DruidSource.listAllDataSources().forEach(DruidDataSource::close);

            WeUtils.getThreadPoolExecutor().shutdown();
            boolean shutdown = false;
            try {
                for (int retry = 0; retry < 3 && !shutdown; retry++) {
                    log.info("waiting for thread pool shutdown...");
                    shutdown = WeUtils.getThreadPoolExecutor().awaitTermination(1000, TimeUnit.MILLISECONDS);
                }
                if (!shutdown) {
                    log.info("thread pool shutdown expired, force exit");
                    WeUtils.getThreadPoolExecutor().shutdownNow();
                }
            } catch (Exception e) {
                log.error(ExceptionUtil.stacktraceToString(e, Integer.MAX_VALUE));
                try {
                    WeUtils.getThreadPoolExecutor().shutdownNow();
                } catch (Exception exception) {
                    log.error(ExceptionUtil.stacktraceToString(e, Integer.MAX_VALUE));
                }
            }
            log.info("wetool exit");
        }));
    }

    private static void checkAlreadyRunning() {
        if (BootConfig.isDebug()) {
            return;
        }

        String pid = String.valueOf(WeUtils.getCurrentPid());
        List<String> javaProcess = RuntimeUtil.execForLines("jps");
        for (String process : javaProcess) {
            process = StrUtil.trim(process);
            if (process.startsWith(pid)) {
                // 排除当前进程
                continue;
            }
            if (StrUtil.containsIgnoreCase(process, "wetool")) {
                log.info("check process[{}] is running", process);
                try {
                    WeStatus.State state = JSON.parseObject(HttpUtil.get("http://127.0.0.1:8189/wetool/status"), WeStatus.class).getState();
                    log.info("process[{}] status: {}", process, state.name().toLowerCase());
                    if (state != WeStatus.State.TERMINATING) {
                        log.info("wetool is already running, show wetool now");
                        HttpUtil.get("http://127.0.0.1:8189/wetool/show");
                        System.exit(0);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    public static void initApp() {
        CronUtil.setMatchSecond(true);
        //解析文件路径
        String path = WeUtils.parsePathByOs("we-plugin-config.json");
        BeanFactory.register(JSON.parseObject(Objects.isNull(path) ? "{}" : FileUtil.readUtf8String(path), WePluginConfig.class));

        // 注册事件
        EventCenter.registerEvent(EventCenter.EVENT_QUICK_START_CLICKED, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_CLEAR_FXML_CACHE, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_WETOOL_RESTART, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_WETOOL_EXIT, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_WETOOL_SHOW, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_WETOOL_HIDDEN, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_CLIPBOARD_CHANGED, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_MOUSE_CORNER_TRIGGER, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_100_MS_TIMER, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_KEYBOARD_PRESSED, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_KEYBOARD_RELEASED, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_MOUSE_MOTION, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_MOUSE_RELEASED, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_MOUSE_PRESSED, EventMode.MULTI_SUB);
        EventCenter.registerEvent(EventCenter.EVENT_ALL_PLUGIN_LOADED, EventMode.MULTI_SUB);
        //暂时注释掉 100ms 心跳发送事件
        //EXECUTOR.scheduleWithFixedDelay(() -> EventCenter.publishEvent(EventCenter.EVENT_100_MS_TIMER, DateUtil.date()), 0, 100, TimeUnit.MILLISECONDS);

        // 初始化键盘鼠标监听器
        initKeyboardMouseListener();

        connectDb();
        exportHttpService();
    }

    private static void initKeyboardMouseListener() {
        FxUtils.listenKeyEvent();
        if (BooleanUtil.isTrue(WeUtils.getConfig().getDisableKeyboardMouseListener())) {
            log.info("disable jnative keyboard mouse listener");
            if (!SystemUtil.getOsInfo().isMac()) {
                // 已知Mac平台下不能正常工作
                log.info("register mouse location listener");
                EventCenter.on100MsTimer(new MouseLocationListenerEventHandler());
            }
            return;
        }

        // 关闭 jnative 日志
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);

        log.info("register jnative keyboard mouse listener");
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(new WeKeyboardListener());
            WeMouseListener nativeMouseListener = new WeMouseListener();
            GlobalScreen.addNativeMouseMotionListener(nativeMouseListener);
            GlobalScreen.addNativeMouseListener(nativeMouseListener);
        } catch (NativeHookException ex) {
            log.error("register keyboard listener failed: {}", ExceptionUtil.stacktraceToString(ex, Integer.MAX_VALUE));
        }
    }

    private static void exportHttpService() {
        WeUtils.execute(() -> {
            try {
                HttpService.exportHttp("get/wetool/hello", (req, resp, param, body) -> ObjectResp.of(param, body));
                HttpService.exportHttp("get/wetool/exit", (req, resp, param, body) -> {
                    Platform.runLater(WeUtils::exitSystem);
                    return ObjectResp.of("status", "success");
                });
                HttpService.exportHttp("get/wetool/show", (req, resp, param, body) -> {
                    FxUtils.showStage();
                    return ObjectResp.of("status", "success");
                });
                HttpService.exportHttp("get/wetool/status", (req, resp, param, body) -> BeanFactory.get(WeStatus.class));
                HttpService.exportHttp("post/wetool/file/upload", (req, resp, param, body) -> {
                    String fileDir = BeanFactory.get(WeConfig.class).getFileUploadDir();
                    if (StrUtil.isBlank(fileDir)) {
                        throw new HttpException().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).setMsg("文件上传未开启");
                    }
                    Https.writeMultipartFiles(req, fileDir);
                    return null;
                });
                HttpService.exportHttp("get/upload.html", (req, resp, param, body) -> Https.responseHtml(resp, ResourceUtil.readUtf8Str("static/upload.html")));
                WeUtils.getConfig().getHttpFiles().forEach(e -> HttpFileBrowserService.getInstance().handleExportCmd(e, false));
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        });
    }

    private static void connectDb() {
        JSONArray jsonArray = WeUtils.getConfig().getDbConnections();
        if (CollUtil.isEmpty(jsonArray)) {
            return;
        }
        WeUtils.execute(() -> {
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                Properties properties = new Properties();
                jsonObject.forEach((k, v) -> properties.put(StrUtil.addPrefixIfNot(k, "druid."), v));
                DruidSource.configDataSource(properties);
            }
        });
    }

    public static void addIntoPluginMenu(MenuItem menuItem) {
        if (ObjectUtils.isNotNull(PLUGIN_MENU, menuItem)) {
            PLUGIN_MENU.add(menuItem);
        }
    }

    private static void parseConfig() {
        // 解析正确的配置文件路径
        String path = WeUtils.parsePathByOs("we-config.json");
        if (StrUtil.isEmpty(path)) {
            log.error("wetool start error: config file not found");
            WeUtils.exitSystem();
        }
        log.info("load config file: {}", path);
        // 解析JSON配置
        JSONObject json = JSON.parseObject(FileUtil.readUtf8String(path), Feature.OrderedField);
        WeConfig config = json.toJavaObject(WeConfig.class);
        config.setConfigJson(json);
        config.setCurrentPath(path);
        BeanFactory.register(config);
        // 检测空指针
        config.init();

        // 配置代理
        String proxy = config.getProxy();
        if (StrUtil.isBlank(proxy)) {
            return;
        }
        int idx = proxy.indexOf(":");
        if (idx < 1) {
            log.warn("proxy config error, format: 127.0.0.1:7889");
        }
        String host = proxy.substring(0, idx);
        String port = proxy.substring(idx + 1);
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        log.info("use proxy -> {}", proxy);
    }

    public static boolean isRootPane() {
        Scene scene = FxUtils.getStage().getScene();
        return Objects.equals(scene, getRootScene()) && Objects.equals(scene.getRoot(), getRootPane());
    }

    private static void setRootPane(Pane rootPane) {
        WeApplication.rootPane = rootPane;
    }

    public static void recoverRootPane() {
        log.info("recover scene root pane");
        FxUtils.getStage().setTitle(FinalUtils.getAppTitle());
        getRootScene().setRoot(getRootPane());
        FxUtils.getStage().setScene(getRootScene());
    }

    private void listenKeyboard() {
        log.info("add javafx keyboard listener");
        FxUtils.getStage().getScene().addEventFilter(EventType.ROOT, event -> {
            if (event instanceof KeyEvent) {
                String eventType = event.getEventType().toString();
                if (eventType.equals("KEY_PRESSED")) {
                    int id = NativeKeyEvent.NATIVE_KEY_PRESSED;
                    NativeKeyEventAdapter adapter = NativeKeyEventAdapter.of(id, (KeyEvent) event);
                    if (FxUtils.getPressingKeyCodes().contains(adapter.getKeyCode())) {
                        return;
                    }
                    KeyboardListenerEventMessage message = KeyboardListenerEventMessage.of(adapter);
                    EventCenter.publishEvent(EventCenter.EVENT_KEYBOARD_PRESSED, DateUtil.date(), message);
                }
                if (eventType.equals("KEY_RELEASED")) {
                    int id = NativeKeyEvent.NATIVE_KEY_RELEASED;
                    NativeKeyEventAdapter adapter = NativeKeyEventAdapter.of(id, (KeyEvent) event);
                    KeyboardListenerEventMessage message = KeyboardListenerEventMessage.of(adapter);
                    EventCenter.publishEvent(EventCenter.EVENT_KEYBOARD_RELEASED, DateUtil.date(), message);
                }
            }
        });
    }

    @Override
    public void start(Stage stage) {
        Thread.currentThread().setName("javafx");
        log.info("starting javafx stage");
        BeanFactory.register(stage);

        // 注册并发布秒钟定时器事件
        String eventKey = EventCenter.EVENT_SECONDS_TIMER;
        EventPublisher publisher = EventCenter.registerEvent(eventKey, EventMode.MULTI_SUB).orElse(null);
        Objects.requireNonNull(publisher);
        EXECUTOR.scheduleWithFixedDelay(publisher::publishEvent, 0, 1, TimeUnit.SECONDS);

        // 加载主界面
        log.info("loading wetool main fxml");
        Pane root = FxUtils.loadFxml(WeApplication.class, ViewConsts.MAIN, false);
        if (Objects.isNull(root)) {
            FxDialogs.showError(TipConsts.INIT_ERROR);
            WeUtils.exitSystem();
        }
        // 设置标题
        setRootPane(root);
        setRootScene(new Scene(Objects.requireNonNull(root)));
        stage.setScene(getRootScene());
        WeUtils.getConfig().darkIfEnabled(stage.getScene().getStylesheets()::add);
        stage.getIcons().add(new Image(getClass().getResourceAsStream(ViewConsts.ICON)));
        stage.setTitle(FinalUtils.getAppTitle());
        // 监听关闭事件
        stage.setOnCloseRequest((WindowEvent event) -> {
            if (isRootPane()) {
                FxUtils.hideStage();
            } else {
                recoverRootPane();
            }
            event.consume();
        });
        // 设置大小
        WeConfig config = WeUtils.getConfig();
        stage.setWidth(config.getInitialize().getWidth());
        stage.setHeight(config.getInitialize().getHeight());
        stage.setFullScreen(config.getInitialize().getFullscreen());

        if (BooleanUtil.isTrue(WeUtils.getConfig().getDisableKeyboardMouseListener())) {
            // 禁用了jnativehook，使用javafx窗体键盘事件
            listenKeyboard();
        }

        stage.setOnShown(windowEvent -> {
            FxUtils.getStage().getScene().getRoot().requestFocus();
            EventCenter.publishEvent(EventCenter.EVENT_WETOOL_SHOW, DateUtil.date());
        });
        stage.setOnHidden(windowEvent -> EventCenter.publishEvent(EventCenter.EVENT_WETOOL_HIDDEN, DateUtil.date()));

        if (BooleanUtil.isTrue(WeUtils.getConfig().getInitialize().getHide())) {
            FxUtils.hideStage();
        } else {
            stage.show();
        }

        // 处理全局异常
        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            ToDialogException dialogCaused = WeUtils.getDialogCaused(throwable);
            if (Objects.isNull(dialogCaused)) {
                FxDialogs.showException(TipConsts.APP_EXCEPTION, throwable);
            } else {
                FxDialogs.showDialog(dialogCaused);
            }
        });
        enableTray(stage);
        log.info("wetool started");
    }

    private void setQuickStartMenu(Menu menu, Set<WeStart> starts) {
        starts.forEach(start -> {
            if (CollUtil.isEmpty(start.getSubStarts())) {
                // 添加子菜单
                MenuItem item = new MenuItem(start.getAlias());
                item.addActionListener(e -> {
                    QuickStartEventMessage message = QuickStartEventMessage.of(start.getLocation());
                    EventCenter.publishEvent(EventCenter.EVENT_QUICK_START_CLICKED, DateUtil.date(), message);
                    FxUtils.openFile(start.getLocation());
                });
                menu.add(item);
                log.info("load quick start tray menu, name: {}, file: {}", start.getAlias(), start.getLocation());
            } else {
                // 添加父级菜单
                Menu subMenu = new Menu(start.getAlias());
                menu.add(subMenu);
                setQuickStartMenu(subMenu, start.getSubStarts());
            }
        });
    }

    /**
     * 开启系统托盘图标
     */
    private void enableTray(Stage stage) {
        log.info("enable tray icon");
        try {
            FXTrayIcon icon = new FXTrayIcon(stage, WeApplication.class.getResource(ViewConsts.ICON));
            icon.setApplicationTitle(FinalUtils.getAppTitle());
            icon.setTrayIconTooltip(FinalUtils.getAppTitle());

            icon.show();

            WeUtils.execute(() -> {
                ThreadUtil.sleep(500);
                TrayIcon trayIcon = SystemTray.getSystemTray().getTrayIcons()[0];
                trayIcon.setPopupMenu(getPopupMenu());
                trayIcon.addMouseListener(new TrayMouseListener());
                trayIcon.setImageAutoSize(true);

                BeanFactory.register(icon);
                BeanFactory.register(trayIcon);
                BeanFactory.register("isTraySuccess", true);
            });
        } catch (Exception e) {
            log.error(ExceptionUtil.stacktraceToString(e, Integer.MAX_VALUE));
        }
    }

    private PopupMenu getPopupMenu() {
        log.info("add tray popup menu");
        // 添加托盘邮件菜单
        PopupMenu popupMenu = new PopupMenu();
        // 快捷打开
     /*   Set<WeStart> starts = WeUtils.getConfig().getQuickStarts();
        if (CollUtil.isNotEmpty(starts)) {
            Menu menu = new Menu(TitleConsts.QUICK_START);
            setQuickStartMenu(menu, starts);
            popupMenu.add(menu);
            popupMenu.addSeparator();
        }*/
        // 插件菜单
        popupMenu.add(PLUGIN_MENU);
        popupMenu.addSeparator();
        // 打开
        Menu menu = new Menu(TitleConsts.OPEN);
        addQuickOpenMenu(menu);
        popupMenu.add(menu);
        popupMenu.addSeparator();
        // 显示
        MenuItem item = new MenuItem(TitleConsts.SHOW);
        item.addActionListener(e -> FxUtils.showStage());
        popupMenu.add(item);
        // 隐藏
        item = new MenuItem(TitleConsts.HIDE);
        item.addActionListener(e -> FxUtils.hideStage());
        popupMenu.add(item);
        // 重启
        popupMenu.addSeparator();
        item = new MenuItem(TitleConsts.RESTART);
        item.addActionListener(e -> FxUtils.restart());
        popupMenu.add(item);
        // 退出
        popupMenu.addSeparator();
        item = new MenuItem(TitleConsts.EXIT);
        item.addActionListener(e -> WeUtils.exitSystem());
        popupMenu.add(item);
        return popupMenu;
    }

    private void addQuickOpenMenu(Menu menu) {
        menu.add(FxUtils.createTrayMenuItem("配置文件", e -> FinalUtils.openConfig()));
        // menu.add(FxUtils.createTrayMenuItem("日志文件", e -> FxUtils.openFile(FileConsts.LOG)));
        menu.addSeparator();
        menu.add(FxUtils.createTrayMenuItem("工作目录", e -> FxUtils.openFile(FileUtils.currentWorkDir())));
        menu.add(FxUtils.createTrayMenuItem("插件目录", e -> FinalUtils.openPluginFolder()));
        //menu.add(FxUtils.createTrayMenuItem("日志目录", e -> FxUtils.openFile(FileConsts.LOG_FOLDER)));
        // menu.addSeparator();
        // menu.add(FxUtils.createTrayMenuItem("插件仓库", e -> FxUtils.openLink(TipConsts.REPO_LINK)));
    }

    private static class TrayMouseListener implements MouseListener {

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == IntegerConsts.TWO) {
                // 双击图标
                Platform.runLater(FxUtils::toggleStage);
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            log.debug("mouse pressed: {}", e.getPoint());
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            log.debug("mouse released: {}", e.getPoint());
        }

        @Override
        public void mouseEntered(MouseEvent e) {
            log.debug("mouse entered: {}", e.getPoint());
        }

        @Override
        public void mouseExited(MouseEvent e) {
            log.debug("mouse exited: {}", e.getPoint());
        }
    }
}
