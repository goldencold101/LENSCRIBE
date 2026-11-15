import pathlib

p = pathlib.Path('C:/Users/USER/Documents/Qoder/2026-11-10/a8f09c88/main.py')
content = p.read_text(encoding='utf-8')

old = 'WAITLIST_PLANS = {plan["id"] for plan in PLANS if not plan["available"]}\n\nENGINES = {"auto", "ollama", "windows", "gemini"}'
new = '''WAITLIST_PLANS = {plan["id"] for plan in PLANS if not plan["available"]}

# --------------------------------------------------------------------------- #
    # Plan resolution (Free, Plus, Pro) - allows per-request selection for testing
# --------------------------------------------------------------------------- #

# Normal users default to "free" unless specified otherwise.
# Useful for shared environments, testing, and free‑tier usage.
def _resolve_plan(request: Request) -> str:
    """Determine the plan from request headers/query, fallback to free."""
    # Try X-Plan header first (case-insensitive)
    plan = request.headers.get("X-Plan", "").strip().lower()
    if plan:
        plan = plan.split(",")[0].strip()
        if plan in {p["id"] for p in PLANS}:
            return plan
    # Then query param - e.g., ?plan=plus
    query = request.query_params.get("plan", "")
    if query:
        query = query.strip().lower()
        if query in {p["id"] for p in PLANS}:
            return query
    # Fallback to free
    return "free"

# Per-plan limits. Adjust before launch.
PLAN_LIMITS = {
    "free": {"requests_per_hour": 20, "image_bytes": 5 * 1024 * 1024, "export_formats": ["txt", "png"]},
    "plus": {"requests_per_hour": 200, "image_bytes": 50 * 1024 * 1024, "export_formats": ["docx", "pdf", "xlsx", "pptx", "csv", "txt", "png"]},
    "pro": {"requests_per_hour": 500, "image_bytes": 200 * 1024 * 1024, "export_formats": ["docx", "pdf", "xlsx", "pptx", "csv", "txt", "png"]},
    "team": {"requests_per_hour": 1000, "image_bytes": 500 * 1024 * 1024, "export_formats": ["docx", "pdf", "xlsx", "pptx", "csv", "txt", "png"]},
}

ENGINES = {"auto", "ollama", "windows", "gemini"}'''

if old in content:
    content = content.replace(old, new)
    p.write_text(content, encoding='utf-8')
    print('SUCCESS: plan resolution + PLAN_LIMITS inserted')
else:
    print('FAIL: old block not found')
