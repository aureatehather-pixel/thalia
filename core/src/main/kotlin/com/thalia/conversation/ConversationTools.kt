package com.thalia.conversation

/**
 * The function declarations every provider is given, as one JSON array of `{name, description, parameters}` with
 * parameters as a JSON Schema object. One source, so the declared names and arguments cannot drift from
 * [OperationParser], which refuses any call that is not exactly one of these.
 */
object ConversationTools {
    val DECLARATIONS_JSON: String =
        """
        [
          {
            "name": "record_outcome",
            "description": "Record what happened to one listed action, only when the person's latest message states it plainly.",
            "parameters": {
              "type": "object",
              "properties": {
                "action_ref": {"type": "string", "description": "Reference of a listed action, such as a1."},
                "result": {"type": "string", "enum": ["done", "partial", "skipped", "blocked"]}
              },
              "required": ["action_ref", "result"]
            }
          },
          {
            "name": "propose_new_action",
            "description": "Suggest a new action. It waits for approval and is not added to today's plan.",
            "parameters": {
              "type": "object",
              "properties": {
                "title": {"type": "string", "description": "What to do, as a short concrete action."},
                "project_ref": {"type": "string", "description": "Reference of a listed project, if it belongs to one."},
                "priority": {"type": "string", "enum": ["must", "important", "normal", "optional"]},
                "estimate_minutes": {"type": "integer", "description": "Rough minutes, 5 to 720. Omit when unknown."},
                "due_date": {"type": "string", "description": "YYYY-MM-DD, only when a real deadline was given."},
                "reason": {"type": "string", "description": "One short sentence: why this helps now."},
                "inbox_ref": {"type": "string", "description": "Reference of a listed Inbox item, such as i1, when this action is made from it. Approval then also takes the item out of the Inbox."}
              },
              "required": ["title"]
            }
          },
          {
            "name": "propose_action_change",
            "description": "Suggest changing the title, priority, estimate or due date of one open listed action. It waits for approval.",
            "parameters": {
              "type": "object",
              "properties": {
                "action_ref": {"type": "string"},
                "title": {"type": "string"},
                "priority": {"type": "string", "enum": ["must", "important", "normal", "optional"]},
                "estimate_minutes": {"type": "integer"},
                "due_date": {"type": "string", "description": "YYYY-MM-DD"},
                "clear_due_date": {"type": "boolean"},
                "reason": {"type": "string"}
              },
              "required": ["action_ref"]
            }
          },
          {
            "name": "propose_day_selection",
            "description": "Suggest exactly which listed open actions make up one day's plan: today's, or the next active day's. Thalia's planner checks it and the person approves. Use day next only to plan the next active day named in the snapshot, for example at a closing check-in; Thalia works out its date.",
            "parameters": {
              "type": "object",
              "properties": {
                "action_refs": {"type": "array", "items": {"type": "string"}},
                "day": {"type": "string", "enum": ["today", "next"]},
                "reason": {"type": "string"}
              },
              "required": ["action_refs"]
            }
          },
          {
            "name": "propose_project_context",
            "description": "Suggest recording one piece of a listed project's context, in the person's meaning. It waits for approval. Only when the person said it.",
            "parameters": {
              "type": "object",
              "properties": {
                "project_ref": {"type": "string"},
                "field": {"type": "string", "enum": ["purpose", "done_when", "constraints", "blocker"]},
                "text": {"type": "string", "description": "Short, in the person's own terms."},
                "reason": {"type": "string"}
              },
              "required": ["project_ref", "field", "text"]
            }
          },
          {
            "name": "propose_fixed_commitment",
            "description": "Suggest a fixed commitment the person stated, at local times. It waits for approval. Ask first if the date or time is unclear.",
            "parameters": {
              "type": "object",
              "properties": {
                "title": {"type": "string"},
                "start": {"type": "string", "description": "Local date and time, YYYY-MM-DDTHH:MM, from the calendar date the person meant."},
                "end": {"type": "string", "description": "Local date and time, YYYY-MM-DDTHH:MM."},
                "reason": {"type": "string"}
              },
              "required": ["title", "start", "end"]
            }
          },
          {
            "name": "propose_reminder",
            "description": "Suggest ONE one-time reminder: a quiet notification around a local time between one minute and 7 days from now, for one listed open action or, with no action_ref, a checkpoint. It waits for approval. Ask first if the day or time is unclear. If the person says what the reminder is for, attach it with action_ref to the one listed open action that clearly matches; if none clearly matches, ask which action or whether they want a generic check-in, instead of dropping what it is for. A checkpoint (no action_ref) carries no description and is only for a generic check-in the person asked for. Not for recurring reminders, snoozing, cancelling or calendars: those are not supported.",
            "parameters": {
              "type": "object",
              "properties": {
                "at": {"type": "string", "description": "Local date and time, YYYY-MM-DDTHH:MM, from the current date and time in the snapshot."},
                "action_ref": {"type": "string", "description": "Reference of the listed open action it is about, when the person named what it is for. Omit only for a generic check-in."},
                "reason": {"type": "string"}
              },
              "required": ["at"]
            }
          },
          {
            "name": "record_focus_capacity",
            "description": "Record today's focused-work capacity in minutes, only when the person's latest message states it plainly.",
            "parameters": {
              "type": "object",
              "properties": {"minutes": {"type": "integer", "description": "0 to 720."}},
              "required": ["minutes"]
            }
          },
          {
            "name": "propose_focus_capacity",
            "description": "Suggest a focused-work capacity you inferred rather than were told. It waits for approval.",
            "parameters": {
              "type": "object",
              "properties": {"minutes": {"type": "integer"}, "reason": {"type": "string"}},
              "required": ["minutes"]
            }
          },
          {
            "name": "resolve_inbox_item",
            "description": "File one listed Inbox item, only when the person's latest message plainly says what to do with it, such as 'save this as a note', 'keep this as an idea', 'archive this' or 'discard this'. The original text is kept unchanged. Never from your own guess, and never to turn it into an action: use propose_new_action with inbox_ref for that.",
            "parameters": {
              "type": "object",
              "properties": {
                "item_ref": {"type": "string", "description": "Reference of a listed Inbox item, such as i1."},
                "outcome": {"type": "string", "enum": ["note", "idea", "archive", "discard"]}
              },
              "required": ["item_ref", "outcome"]
            }
          },
          {
            "name": "propose_inbox_resolution",
            "description": "Suggest filing a listed Inbox item that the person did not plainly decide, or filing it with a body you rewrote. It waits for approval. Leave body out to keep the original text.",
            "parameters": {
              "type": "object",
              "properties": {
                "item_ref": {"type": "string"},
                "outcome": {"type": "string", "enum": ["note", "idea", "archive", "discard"]},
                "body": {"type": "string", "description": "Only for a note or an idea, only when you changed the wording."},
                "reason": {"type": "string"}
              },
              "required": ["item_ref", "outcome"]
            }
          },
          {
            "name": "ask_clarification",
            "description": "Ask one question when the target or meaning is unclear. Changes nothing.",
            "parameters": {
              "type": "object",
              "properties": {"question": {"type": "string"}},
              "required": ["question"]
            }
          }
        ]
        """.trimIndent()
}
