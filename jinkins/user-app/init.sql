CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL
);

INSERT INTO users (name, email)
VALUES
    ('Fatma', 'fatma@example.com'),
    ('Elias', 'elias@example.com')
ON CONFLICT (email) DO NOTHING;
