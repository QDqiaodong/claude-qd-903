package com.port.terminal.common;

/**
 * 乐观锁冲突：两名调度员并发提交同一接管方案时，后提交者必须重新查看方案变化。
 * 全局异常处理器转为 409。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
