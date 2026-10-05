package com.fc.v2.service;

import java.util.Date;
import java.util.List;
import com.fc.v2.model.auto.TEdCourseTask;

/**
 * TEdCourseTask 业务层处理接口
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITEdCourseTaskService {

    /** selectTEdCourseTaskById */
    TEdCourseTask selectTEdCourseTaskById(Long id);

    /** listDue */
    List<TEdCourseTask> listDue(Date at);

    /** runOnce */
    int runOnce(Date at);

}
