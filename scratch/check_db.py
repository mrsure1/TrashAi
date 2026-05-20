import os
from supabase import create_client

def get_config():
    config = {}
    p = 'local.properties'
    if os.path.exists(p):
        with open(p, "r", encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    key, val = line.strip().split("=", 1)
                    config[key] = val.strip('"')
    return config

def main():
    config = get_config()
    url = config.get("SUPABASE_URL")
    key = config.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("Missing Supabase configuration.")
        return

    supabase = create_client(url, key)
    try:
        data = supabase.table("waste_disposal_rules").select("id, item_name, category, disposal_method").limit(100).execute().data
        with open("scratch/db_output.txt", "w", encoding="utf-8") as out:
            out.write(f"Total retrieved rows: {len(data)}\n")
            for i, row in enumerate(data):
                out.write(f"[{i+1}] ID: {row.get('id')}, 품목명: {row.get('item_name')}, 카테고리: {row.get('category')}, 배출방법: {row.get('disposal_method')[:80]}...\n")
        print("Success! Output written to scratch/db_output.txt")
    except Exception as e:
        print("Error querying database:", e)

if __name__ == "__main__":
    main()
