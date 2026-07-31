update merchant_rich_menus
set status = 'PENDING',
    last_error = null,
    updated_at = current_timestamp
where line_rich_menu_id is not null;

update merchant_rich_menu_sync
set status = 'READY',
    attempts = 0,
    next_attempt_at = current_timestamp,
    locked_at = null,
    last_error = null,
    updated_at = current_timestamp
where desired_linked = true;
