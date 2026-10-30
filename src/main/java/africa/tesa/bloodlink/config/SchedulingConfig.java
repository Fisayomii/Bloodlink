package africa.tesa.bloodlink.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled methods: the hold and expiry sweep (HoldAndExpirySweep). The sweep itself can be switched off with
 * app.sweep.enabled=false, as the tests do, so they can check what reads show before any job has run.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
