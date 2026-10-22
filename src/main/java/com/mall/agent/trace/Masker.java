package com.mall.agent.trace;

import java.util.regex.Pattern;

/** 日志与 Trace 里的敏感信息脱敏:16 位订单号只留后 4 位,手机号中间 4 位打码。 */
final class Masker {

    private static final Pattern ORDER_NO = Pattern.compile("(?<!\\d)(\\d{12})(\\d{4})(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");

    private Masker() {
    }

    static String mask(String s) {
        if (s == null) {
            return null;
        }
        String masked = ORDER_NO.matcher(s).replaceAll("************$2");
        return PHONE.matcher(masked).replaceAll("$1****$2");
    }
}
