package com.btrace.testfake;

/**
 * 测试用 AIDL 风格接口。MethodResolver 反射档需要一个稳定的、可控的 $Stub 类
 * 来验证 TRANSACTION_* 字段抽取逻辑,使用 framework 的 IActivityManager 受 Robolectric/SDK 版本影响。
 */
public interface ITestStub {
    String doFoo(int a, String b);

    void doBar(java.util.List<String> items, android.os.Bundle bundle);

    final class Stub {
        public static final int TRANSACTION_doFoo = 1;
        public static final int TRANSACTION_doBar = 2;

        // 干扰字段:非 int / 非 static / 非 TRANSACTION_ 前缀,应被忽略
        public static final String NON_TRANSACTION = "ignore me";
        public static final long TRANSACTION_wrongType = 99L;
        public final int TRANSACTION_notStatic = 7;
    }
}
