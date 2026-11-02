package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub only.
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class Unhook {
        public Member getHookedMethod() {
            throw new UnsupportedOperationException("stub");
        }

        public void unhook() {
            throw new UnsupportedOperationException("stub");
        }
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }
    }
}
