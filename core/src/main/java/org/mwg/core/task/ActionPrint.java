package org.mwg.core.task;

import org.mwg.plugin.AbstractTaskAction;
import org.mwg.task.TaskAction;
import org.mwg.task.TaskContext;
import org.mwg.task.TaskResult;

class ActionPrint extends AbstractTaskAction {

    private final String _name;

    ActionPrint(final String p_name) {
        super();
        this._name = p_name;
    }

    @Override
    public void eval(final TaskContext context) {
        /*if (context.isVerbose()) {
            for (int i = 0; i < context.ident() + 1; i++) {
                System.out.print("\t");
            }
            System.out.println(context.template(_name));
        } else {
        */
        System.out.println(context.template(_name));
        //}
        context.continueTask();
    }

    @Override
    public String toString() {
        return "print(\'" + _name + "\')";
    }

}
