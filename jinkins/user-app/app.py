import os
import time
from flask import Flask, jsonify, request
import psycopg2
from psycopg2.extras import RealDictCursor

app = Flask(__name__)

# ---- DB config (تجيلي من environment variables، مش هاردكودد) ----
DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = os.environ.get("DB_PORT", "5432")
DB_NAME = os.environ.get("DB_NAME", "appdb")
DB_USER = os.environ.get("DB_USER", "appuser")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "apppassword")


def get_connection():
    """
    بتحاول تعمل connection لل DB.
    بنعمل retry بسيط لان في docker-compose ممكن ال app يقوم قبل ال DB يخلص initialize.
    """
    retries = 5
    last_error = None
    for attempt in range(retries):
        try:
            conn = psycopg2.connect(
                host=DB_HOST,
                port=DB_PORT,
                dbname=DB_NAME,
                user=DB_USER,
                password=DB_PASSWORD,
                connect_timeout=3,
            )
            return conn
        except Exception as e:
            last_error = e
            time.sleep(2)
    raise last_error


def init_db():
    """بتعمل الجدول لو مش موجود. بتتنادى مرة واحدة وقت ما ال app يشتغل."""
    conn = get_connection()
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS tasks (
            id SERIAL PRIMARY KEY,
            title VARCHAR(255) NOT NULL,
            done BOOLEAN DEFAULT FALSE,
            created_at TIMESTAMP DEFAULT NOW()
        );
        """
    )
    conn.commit()
    cur.close()
    conn.close()


# ---------------- Health check ----------------
# مهم جدا: ال pipeline هيستخدم ده في مرحلة ال Test بعد كل deploy
@app.route("/health", methods=["GET"])
def health():
    # عمداً بيفشل دايماً - نسخة اختبار لتجربة الـ rollback فقط
    return jsonify({"status": "error", "detail": "intentional failure for rollback test"}), 500


# ---------------- CRUD: Tasks ----------------
@app.route("/tasks", methods=["GET"])
def get_tasks():
    conn = get_connection()
    cur = conn.cursor(cursor_factory=RealDictCursor)
    cur.execute("SELECT * FROM tasks ORDER BY id;")
    tasks = cur.fetchall()
    cur.close()
    conn.close()
    return jsonify(tasks), 200


@app.route("/tasks", methods=["POST"])
def create_task():
    data = request.get_json()
    title = data.get("title")
    if not title:
        return jsonify({"error": "title is required"}), 400

    conn = get_connection()
    cur = conn.cursor(cursor_factory=RealDictCursor)
    cur.execute(
        "INSERT INTO tasks (title) VALUES (%s) RETURNING *;", (title,)
    )
    new_task = cur.fetchone()
    conn.commit()
    cur.close()
    conn.close()
    return jsonify(new_task), 201


@app.route("/tasks/<int:task_id>", methods=["PUT"])
def update_task(task_id):
    data = request.get_json()
    done = data.get("done")

    conn = get_connection()
    cur = conn.cursor(cursor_factory=RealDictCursor)
    cur.execute(
        "UPDATE tasks SET done = %s WHERE id = %s RETURNING *;",
        (done, task_id),
    )
    updated = cur.fetchone()
    conn.commit()
    cur.close()
    conn.close()

    if not updated:
        return jsonify({"error": "task not found"}), 404
    return jsonify(updated), 200


@app.route("/tasks/<int:task_id>", methods=["DELETE"])
def delete_task(task_id):
    conn = get_connection()
    cur = conn.cursor()
    cur.execute("DELETE FROM tasks WHERE id = %s;", (task_id,))
    deleted = cur.rowcount
    conn.commit()
    cur.close()
    conn.close()

    if deleted == 0:
        return jsonify({"error": "task not found"}), 404
    return jsonify({"message": "deleted"}), 200


if __name__ == "__main__":
    init_db()
    app.run(host="0.0.0.0", port=5000)
