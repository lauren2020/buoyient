package com.les.databuoy.examples.todo

import com.les.databuoy.ServiceRequestTag

enum class TodoRequestTag(override val value: String) : ServiceRequestTag {
    CREATE_TODO("create_todo"),
    UPDATE_TODO("update_todo"),
    COMPLETE_TODO("complete_todo"),
    VOID_TODO("void_todo"),
}
