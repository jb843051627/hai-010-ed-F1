package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * t_ed_course_task_log 对象 选课催办派单留痕
 *
 * @author fuce
 * @date 2026-10-04
 */
@TableName("t_ed_course_task_log")
@ApiModel(value = "TEdCourseTaskLog", description = "t_ed_course_task_log")
public class TEdCourseTaskLog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 催办事项id */
    @TableField("task_id")
    @ApiModelProperty(value = "催办事项id")
    private Long taskId;

    /** 选课催办事项号（冗余，便于翻查） */
    @TableField("item_no")
    @ApiModelProperty(value = "选课催办事项号")
    private String itemNo;

    /** 动作 DISPATCH派单 SUSPEND挂起 CANCEL撤单 */
    @TableField("action")
    @ApiModelProperty(value = "动作 DISPATCH派单 SUSPEND挂起 CANCEL撤单")
    private String action;

    /** 派单档 DAY日常 PEAK高峰（仅派单动作有值） */
    @TableField("slot")
    @ApiModelProperty(value = "派单档 DAY日常 PEAK高峰")
    private String slot;

    /** 受派辅导员（仅派单动作有值） */
    @TableField("counselor")
    @ApiModelProperty(value = "受派辅导员")
    private String counselor;

    /** 原由与记要 */
    @TableField("detail")
    @ApiModelProperty(value = "原由与记要")
    private String detail;

    /** 落痕时间 */
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "落痕时间")
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getItemNo() {
        return itemNo;
    }

    public void setItemNo(String itemNo) {
        this.itemNo = itemNo;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public String getCounselor() {
        return counselor;
    }

    public void setCounselor(String counselor) {
        this.counselor = counselor;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

}
