package org.mwg.importer;

import org.mwg.Callback;
import org.mwg.importer.util.JsonMemberResult;
import org.mwg.plugin.SchedulerAffinity;
import org.mwg.task.Action;
import org.mwg.task.Task;
import org.mwg.task.TaskContext;
import org.mwg.task.TaskResult;

class ActionJsonMatch implements Action {

    private String _name;
    private org.mwg.task.Task _then;

    ActionJsonMatch(String name, Task then) {
        this._name = name;
        this._then = then;
    }

    @Override
    public void eval(TaskContext ctx) {
        TaskResult result = ctx.result();
        if (result instanceof JsonMemberResult) {
            if (_name.equals(result.get(0).toString())) {
                _then.executeFrom(ctx, (TaskResult) result.get(1), SchedulerAffinity.SAME_THREAD, new Callback<TaskResult>() {
                    @Override
                    public void on(TaskResult res) {
                        ctx.continueWith(res);
                    }
                });
            } else {
                ctx.continueTask();
            }
        } else {
            ctx.continueTask();
        }
    }

}