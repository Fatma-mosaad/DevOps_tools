from flask import Flask, jsonify
import psycopg2

app = Flask(__name__)


def get_db_connection():
    return psycopg2.connect(
        host="postgres",
        database="usersdb",
        user="appuser",
        password="app_password",
        port=5432
    )


@app.route("/health")
def health():
    return jsonify({"status": "ok"})


@app.route("/users")
def users():
    conn = get_db_connection()
    cursor = conn.cursor()

    cursor.execute("SELECT id, name, email FROM users;")
    rows = cursor.fetchall()

    cursor.close()
    conn.close()

    users_list = []

    for row in rows:
        users_list.append({
            "id": row[0],
            "name": row[1],
            "email": row[2]
        })

    return jsonify(users_list)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
