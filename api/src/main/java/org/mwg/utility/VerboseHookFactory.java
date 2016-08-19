package org.mwg.utility;

import org.mwg.task.TaskHook;
import org.mwg.task.TaskHookFactory;

class VerboseHookFactory implements TaskHookFactory {
    @Override
    public TaskHook newHook() {
        return new VerboseHook();
    }
}
