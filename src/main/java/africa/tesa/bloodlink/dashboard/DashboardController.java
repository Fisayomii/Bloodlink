package africa.tesa.bloodlink.dashboard;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /dashboard: bank staff (their own bank) and the administrator (every bank, or one). Safe. */
@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    Dashboard get(@RequestParam(required = false) UUID bankId) {
        return dashboard.forCaller(bankId);
    }
}
