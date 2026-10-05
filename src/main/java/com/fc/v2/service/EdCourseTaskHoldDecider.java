package com.fc.v2.service;

import com.fc.v2.model.auto.TEdCourseTask;

/**
 * 派单前"办不办得成"判定口。
 * <p>提醒浮出来前学生退学休学、或辅导员整组调走的，由实现方给出挂起原由；
 * 返回 null 表示这条照常往下派。学籍/编制系统接入时替换此接口的实现即可，
 * 派单主流程不变。</p>
 *
 * @author fuce
 * @date 2026-10-04
 */
public interface EdCourseTaskHoldDecider {

    /**
     * @param task 本轮到期待派的提醒
     * @return 非空＝就地挂起的原由；null＝可派
     */
    String holdReason(TEdCourseTask task);
}
