import sqlite3
import sys

# 표준 출력을 UTF-8로 강제설정 (또는 파일에 직접 작성)
sys.stdout.reconfigure(encoding='utf-8')

db_path = "app/src/main/assets/wasteguide.sqlite3"
conn = sqlite3.connect(db_path)
cursor = conn.cursor()

output_lines = []

# 1. 컵이나 종이 또는 커피가 포함된 품목 검색
output_lines.append("--- app_item_rule 검색 ---")
cursor.execute("SELECT item_id, item_name FROM app_item_rule WHERE item_name LIKE '%컵%' OR item_name LIKE '%종이%' OR item_name LIKE '%커피%' LIMIT 100")
rows = cursor.fetchall()
for r in rows:
    output_lines.append(f"ID: {r[0]} | Name: {r[1]}")

output_lines.append("\n--- app_search_keyword 검색 ---")
cursor.execute("""
    SELECT k.target_id, r.item_name, k.keyword, k.weight 
    FROM app_search_keyword k
    JOIN app_item_rule r ON r.item_id = k.target_id
    WHERE k.keyword LIKE '%종이컵%' OR k.keyword LIKE '%커피%' OR k.keyword LIKE '%컵%'
    LIMIT 100
""")
rows = cursor.fetchall()
for r in rows:
    output_lines.append(f"ID: {r[0]} | Item: {r[1]} | Keyword: {r[2]} | Weight: {r[3]}")

# 결과를 파일에 기록
with open("scratch/db_search_output.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(output_lines))

print("Done! Results written to scratch/db_search_output.txt")
conn.close()
