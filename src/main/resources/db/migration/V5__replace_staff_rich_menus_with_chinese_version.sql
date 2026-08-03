-- LINE doesn't allow replacing the image of an existing rich menu. Clear the
-- stored references so the worker creates the versioned zh-TW menus and links
-- every active staff member to the new resource.
update merchant_rich_menus
set line_rich_menu_id = null,
    status = 'PENDING',
    last_error = null,
    updated_at = current_timestamp;

update merchant_rich_menu_sync
set revision = revision + 1,
    status = 'READY',
    attempts = 0,
    next_attempt_at = current_timestamp,
    locked_at = null,
    last_error = null,
    updated_at = current_timestamp
where desired_linked = true;
