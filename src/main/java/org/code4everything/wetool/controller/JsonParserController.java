package org.code4everything.wetool.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONPath;
import com.zhazhapan.util.Checker;
import com.zhazhapan.util.Formatter;
import com.zhazhapan.util.ThreadPool;
import com.zhazhapan.util.dialog.Alerts;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import org.code4everything.wetool.WeUtils;
import org.code4everything.wetool.constant.LocalValueConsts;
import org.code4everything.wetool.factor.BeanFactory;
import org.code4everything.wetool.model.ConfigModel;

/**
 * @author pantao
 * @since 2018/3/31
 */
public class JsonParserController {

    @FXML
    public TextArea jsonContent;

    @FXML
    public TextArea parsedJsonContent;

    @FXML
    public TextField jsonPath;

    @FXML
    private void initialize() {
        jsonContent.setWrapText(ConfigModel.isAutoWrap());
        parsedJsonContent.setWrapText(ConfigModel.isAutoWrap());
        BeanFactory.register(this);
    }

    public void parseJson() {
        String json = jsonContent.getText();
        String path = jsonPath.getText();
        ThreadPool.executor.submit(() -> {
            try {
                JSONArray jsonArray = JSON.parseArray("[" + json + "]");
                Object object = JSONPath.eval(jsonArray, "[0]");
                String parsedJson;
                if (Checker.isEmpty(path)) {
                    parsedJson = object.toString();
                } else {
                    parsedJson = JSONPath.eval(object, (object instanceof JSONArray ? "" : ".") + path).toString();
                }
                Platform.runLater(() -> parsedJsonContent.setText(Formatter.formatJson(Checker.checkNull(parsedJson))));
            } catch (Exception e) {
                Platform.runLater(() -> Alerts.showError(LocalValueConsts.MAIN_TITLE,
                                                         LocalValueConsts.PARSE_JSON_ERROR));
            }
        });
    }

    public void seeJsonPathGrammar() {
        WeUtils.openLink(LocalValueConsts.JSON_PATH_GRAMMAR_URL);
    }

    public void dragFileOver(DragEvent event) {
        event.acceptTransferModes(TransferMode.COPY);
    }

    public void dragFileDropped(DragEvent event) {
        WeUtils.putDragFileInTextArea(jsonContent, event);
    }

    public void jsonPathEnter(KeyEvent keyEvent) {
        if (keyEvent.getCode() == KeyCode.ENTER) {
            parseJson();
        }
    }
}
