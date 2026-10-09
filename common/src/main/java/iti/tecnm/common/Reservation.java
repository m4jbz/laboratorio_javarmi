package iti.tecnm.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Una reservación vigente o futura de una computadora: quién la tiene y de qué hora a qué hora. */
public record Reservation(String code, int computerId, String computerName, String studentId,
                          LocalDateTime start, LocalDateTime end) implements Serializable {

    /** Dos horarios se traslapan si cada uno empieza antes de que el otro termine. */
    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    public boolean isActive(LocalDateTime now) {
        return !start.isAfter(now) && end.isAfter(now);
    }
}
