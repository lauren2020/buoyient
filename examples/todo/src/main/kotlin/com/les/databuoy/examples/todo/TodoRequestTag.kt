package com.les.databuoy.examples.todo

import com.les.databuoy.ServiceRequestTag

enum class TodoRequestTag(override val value: String) : ServiceRequestTag {
    CREATE_TODO("create_todo"),
    EDIT_TODO("edit_todo"),
    COMPLETE_TODO("complete_todo"),
    REMOVE_TODO("remove_todo"),
}
