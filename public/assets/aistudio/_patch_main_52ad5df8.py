import pathlib

p = pathlib.Path('C:/Users/USER/Documents/Qoder/2026-11-10/a8f09c88/main.py')
content = p.read_text(encoding='utf-8')

# 1. Insert plan resolution and PLAN_LIMITS after WAITLIST_PLANS
old = 'WAITLIST_PLANS = {plan["id"] for plan in PLANS if not plan["available"]}'
new = '''WAITLIST_PLANS = {plan["id"] for plan in PLANS if not plan["available"]}

# --------------------------------------------------------------------------- #
    # Plan resolution (Free, Plus, Pro) - allows per-request selection for testing
# --------------------------------------------------------------------------- #

# Normal users default to "free" unless specified otherwise.
# Useful for shared environments, testing, and free-tier usage.
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
    print('Replaced plan block')
else:
    print('Old block not found')

# 2. Add Plus plan to PLANS tuple (right after Pro entry)
old_plans = '''        "available": False,
        "featured": True,
    },
    {
        "id": "team",'''
new_plans = '''        "available": False,
        "featured": True,
    },
    {
        "id": "plus",
        "name": "Plus",
        "price": "$5",
        "period": "per month",
        "tagline": "Professional daily use with generous processing.",
        "features": [
            "Everything in Free",
            "Everything in Pro",
            "Unlimited scans per day",
            "Higher priority processing",
            "Early access to new features",
        ],
        "cta": "Upgrade to Plus",
        "available": False,
        "featured": False,
    },
    {
        "id": "team",'''
if old_plans in content:
    content = content.replace(old_plans, new_plans)
    print('Added Plus plan to PLANS')
else:
    print('Plus plan block not found')

# 3. Update transcribe endpoint to resolve plan and use plan-specific limiter
old_transcribe = '''    async def transcribe(request: Request) -> dict:
        await _guard(request, limiter)'''
new_transcribe = '''    async def transcribe(request: Request) -> dict:
        plan = _resolve_plan(request)
        plan_limiter = RateLimiter(PLAN_LIMITS[plan].get("requests_per_hour", 30), settings.rate_limit_window)
        await _guard(request, plan_limiter)'''
if old_transcribe in content:
    content = content.replace(old_transcribe, new_transcribe)
    print('Updated transcribe endpoint')
else:
    print('Transcribe endpoint not found')

p.write_text(content, encoding='utf-8')
print('Done writing main.py')
