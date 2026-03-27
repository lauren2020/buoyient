package com.elvdev.buoyient.examples.todo

import com.elvdev.buoyient.ServiceRequestTag

enum class TodoRequestTag(override val value: String) : ServiceRequestTag {
    CREATE_TODO("create_todo"),
    UPDATE_TODO("update_todo"),
    COMPLETE_TODO("complete_todo"),
    VOID_TODO("void_todo"),
}
