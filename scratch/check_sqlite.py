import sqlite3
import os

def main():
    db_path = "app/src/main/assets/wasteguide.sqlite3"
    if not os.path.exists(db_path):
        print(f"Database not found at: {db_path}")
        return

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    try:
        # 1. 테이블 목록 조회
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
        tables = [row[0] for row in cursor.fetchall()]
        print(f"Tables in SQLite: {tables}")

        with open("scratch/sqlite_output.txt", "w", encoding="utf-8") as out:
            out.write(f"Tables in SQLite: {tables}\n\n")

            for t in tables:
                # 2. 각 테이블 스키마 조회
                cursor.execute(f"PRAGMA table_info({t});")
                schema = cursor.fetchall()
                out.write(f"--- Table: {t} schema ---\n")
                for col in schema:
                    out.write(f"  Col: {col[1]} ({col[2]})\n")
                
                # 3. 데이터 10개 조회
                cursor.execute(f"SELECT * FROM {t} LIMIT 15;")
                rows = cursor.fetchall()
                out.write(f"\nSample data (15 rows):\n")
                for row in rows:
                    out.write(f"  {row}\n")
                out.write("\n" + "="*50 + "\n\n")

        print("Success! Output written to scratch/sqlite_output.txt")
    except Exception as e:
        print("Error reading SQLite database:", e)
    finally:
        conn.close()

if __name__ == "__main__":
    main()
