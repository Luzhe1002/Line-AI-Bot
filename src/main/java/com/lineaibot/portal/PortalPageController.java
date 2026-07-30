package com.lineaibot.portal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PortalPageController {

    @GetMapping({"/portal", "/portal/"})
    String portal() {
        return "forward:/portal/index.html";
    }

    @GetMapping("/booking/{tenantSlug:[a-z0-9][a-z0-9-]*}")
    String booking() {
        return "forward:/booking/index.html";
    }

    @GetMapping("/merchant-booking/{tenantSlug:[a-z0-9][a-z0-9-]*}")
    String merchantBooking() {
        return "forward:/merchant-booking/index.html";
    }
}
