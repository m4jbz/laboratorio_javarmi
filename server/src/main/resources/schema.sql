-- El servidor ejecuta este script cada vez que arranca; IF NOT EXISTS y ON CONFLICT conservan los datos existentes.

-- Inventario: las 20 computadoras. Solo se guarda si están en mantenimiento; "Reservado" no se guarda,
-- se calcula a partir de las reservaciones vigentes.
CREATE TABLE IF NOT EXISTS computer (
    id     INTEGER PRIMARY KEY,
    name   VARCHAR(10) NOT NULL UNIQUE,
    status VARCHAR(12) NOT NULL DEFAULT 'AVAILABLE'
           CHECK (status IN ('AVAILABLE', 'MAINTENANCE'))
);

-- Comprobantes de reserva (ReservationTicket). Una computadora puede tener varias en horarios distintos.
-- Una reservación cancelada conserva su comprobante y tiene fecha en cancelled_at.
CREATE TABLE IF NOT EXISTS reservation (
    code         VARCHAR(16) PRIMARY KEY,
    computer_id  INTEGER     NOT NULL REFERENCES computer (id),
    student_id   VARCHAR(20) NOT NULL,
    start_time   TIMESTAMP   NOT NULL,
    end_time     TIMESTAMP   NOT NULL CHECK (end_time > start_time),
    issued_at    TIMESTAMP   NOT NULL,
    signature    CHAR(64)    NOT NULL,
    cancelled_at TIMESTAMP
);

-- Bitácora: cada solicitud de cambio, aceptada o rechazada, y las liberaciones automáticas ("sistema").
CREATE TABLE IF NOT EXISTS log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    computer_id INTEGER     NOT NULL REFERENCES computer (id),
    username    VARCHAR(20) NOT NULL,
    old_status  VARCHAR(12) NOT NULL,
    new_status  VARCHAR(12) NOT NULL,
    accepted    BOOLEAN     NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    detail      VARCHAR(60)
);

-- Usuarios. La contraseña se guarda con sal y PBKDF2, nunca en texto plano.
CREATE TABLE IF NOT EXISTS app_user (
    username      VARCHAR(20)  PRIMARY KEY,
    password_hash VARCHAR(200) NOT NULL,
    role          VARCHAR(7)   NOT NULL CHECK (role IN ('ADMIN', 'STUDENT'))
);

-- Actualización de bases creadas con la versión anterior (una sola reserva por equipo).
UPDATE computer SET status = 'AVAILABLE' WHERE status = 'RESERVED';
ALTER TABLE computer DROP COLUMN IF EXISTS reserved_by;
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
ALTER TABLE log ADD COLUMN IF NOT EXISTS detail VARCHAR(60);

CREATE INDEX IF NOT EXISTS reservation_computer_time ON reservation (computer_id, start_time);

-- Las 20 computadoras, PC-01 a PC-20.
INSERT INTO computer (id, name)
SELECT n, 'PC-' || lpad(n::text, 2, '0') FROM generate_series(1, 20) AS n
ON CONFLICT (id) DO NOTHING;
