package com.wanxiang.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备自动配对的「机型脚本」模型：把服务端下发的 JSON 脚本解析成内存对象，
 * 供 {@link DeviceScriptEngine} 通用引擎逐步执行。
 *
 * <p>一份脚本描述一台机型（按「厂商+型号」匹配）的「无线调试自动配对」动作序列，
 * 例如 Moto E14：进开发者选项 → 点「无线调试」→ 点「使用无线调试」开关 →
 * 点「允许」→ 点「使用配对码配对设备」→ 读 6 位配对码并本地配对。</p>
 *
 * <p><b>终态约定</b>：脚本最后一步应是 {@code type:"end"}。引擎不以「步骤数组走完」
 * 判断结束（不同机型步数不同），而是以「配对是否真正成功」这一可验证状态作为
 * 唯一成功终态——由 readPair 触发的本地配对异步回调驱动。</p>
 *
 * <p>脚本是「配置」不是「命令」：由服务端下发、本地缓存，执行时才由引擎读取。
 * 加新机型 = 服务端加一份脚本，无需改 APK。</p>
 */
public final class DeviceScript {

    /** 脚本 ID（服务端生成）。 */
    public final String scriptId;

    /** 脚本名称（人看的，后台展示用）。 */
    public final String name;

    /** 脚本版本号，用于设备端「版本比较」避免旧消息覆盖新脚本。 */
    public final int version;

    /** 动作步骤列表，按顺序执行。 */
    public final List<Step> steps;

    private DeviceScript(String scriptId, String name, int version, List<Step> steps) {
        this.scriptId = scriptId;
        this.name = name;
        this.version = version;
        this.steps = steps;
    }

    /**
     * 从服务端下发的 JSON 字符串解析脚本。解析失败返回 null（调用方记日志、
     * 保留旧缓存）。未知字段忽略、缺字段用默认值，保证前后版本兼容。
     */
    public static DeviceScript parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            String scriptId = obj.optString("scriptId", "");
            String name = obj.optString("name", "");
            int version = obj.optInt("version", 0);

            List<Step> steps = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("steps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    Step step = Step.parse(s);
                    if (step != null) {
                        steps.add(step);
                    }
                }
            }
            return new DeviceScript(scriptId, name, version, steps);
        } catch (Exception e) {
            DebugConsole.error("SCRIPT", "解析机型脚本失败: " + e.getMessage());
            return null;
        }
    }

    /** 单个动作步骤。 */
    public static final class Step {
        /** 动作类型：launch / click / readPair / waitFor / end。 */
        public final String type;

        /** launch：要打开的 Intent action 名（如 ACTION_APPLICATION_DEVELOPMENT_SETTINGS）。 */
        public final String action;

        /** click：匹配方式（text/id/desc/class），默认 text。 */
        public final String by;

        /** click：候选文案列表（命中任一即匹配）。 */
        public final List<String> value;

        /** click：要找的节点类型，"switch" 表示找 Switch（复用「向上找父级含文本」逻辑）。 */
        public final String node;

        /** click：找不到时是否向下滚动查找。 */
        public final boolean scroll;

        /** click：单步最大滚动次数。 */
        public final int scrollMax;

        /** click：找不到时是否不失败（可选步骤，兜底用）。 */
        public final boolean optional;

        /** readPair：配对码正则（默认 6 位数字）。 */
        public final String codeRegex;

        private Step(String type, String action, String by, List<String> value, String node,
                     boolean scroll, int scrollMax, boolean optional, String codeRegex) {
            this.type = type;
            this.action = action;
            this.by = by;
            this.value = value;
            this.node = node;
            this.scroll = scroll;
            this.scrollMax = scrollMax;
            this.optional = optional;
            this.codeRegex = codeRegex;
        }

        static Step parse(JSONObject s) {
            String type = s.optString("type", "");
            if (type.isEmpty()) {
                return null;
            }
            String action = s.optString("action", "");
            String by = s.optString("by", "text");
            String node = s.optString("node", "");
            boolean scroll = s.optBoolean("scroll", false);
            int scrollMax = s.optInt("scrollMax", 12);
            boolean optional = s.optBoolean("optional", false);
            String codeRegex = s.optString("codeRegex", "^\\d{6}$");

            List<String> value = new ArrayList<>();
            Object v = s.opt("value");
            if (v instanceof JSONArray) {
                JSONArray va = (JSONArray) v;
                for (int i = 0; i < va.length(); i++) {
                    value.add(va.optString(i));
                }
            } else if (v instanceof String && !((String) v).isEmpty()) {
                value.add((String) v);
            }

            return new Step(type, action, by, value, node, scroll, scrollMax, optional, codeRegex);
        }
    }
}
