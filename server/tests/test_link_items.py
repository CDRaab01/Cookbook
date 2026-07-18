"""Link items (v0.5): pasted product URLs become readable rows with a tappable link.

Covers the pure split/slug tables, the best-effort title fetch, and the add/PATCH endpoints —
including the regression that started it all: a >255-char pasted name must 422, never 500
(the 500 stranded a ghost row in the adding phone's offline mirror).
"""

import httpx
import pytest

from app.lists.link_items import name_from_url, split_link
from app.services.link_title_service import LinkPreview, resolve_link_preview

MEIJER_URL = (
    "https://www.meijer.com/shopping/product/haakaa-ladybug-breast-milk-collectors--2-5-oz--2-pk"
    "/942006020367.html?gclsrc=aw.ds&&cmpid=PMAX:LIA:20459226622:::Google&gad_source=1"
    "&gad_campaignid=20449858206&gbraid=0AAAAADixm5ABc8cOF0062WUDm6W6aciQL"
    "&gclid=EAIaIQobChMI59PNycbZIQMV9H9vBB0OTTC1EAQYAiACEgJ-jfD_BwE"
)

# ── split_link ───────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("text", "typed", "url"),
    [
        ("milk", "milk", None),
        ("  milk   collector  ", "milk collector", None),
        ("https://example.com/x", "", "https://example.com/x"),
        (f"milk collector {MEIJER_URL}", "milk collector", MEIJER_URL),
        (f"{MEIJER_URL} milk collector", "milk collector", MEIJER_URL),
        # \S+ grabs closing punctuation; it must come off the URL, and stay off the name.
        ("look (https://example.com/x)", "look", "https://example.com/x"),
        ("see https://example.com/x.", "see", "https://example.com/x"),
        ("buy https://example.com/x, thanks", "buy thanks", "https://example.com/x"),
        # Extra URLs are dropped entirely — never useful as name text.
        ("a https://one.com/x b https://two.com/y c", "a b c", "https://one.com/x"),
        ("HTTPS://EXAMPLE.COM/X", "", "HTTPS://EXAMPLE.COM/X"),
    ],
)
def test_split_link(text, typed, url):
    assert split_link(text) == (typed, url)


# ── name_from_url ────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("url", "name"),
    [
        # The id segment and .html are skipped; the human slug wins.
        (MEIJER_URL, "haakaa ladybug breast milk collectors 2 5 oz 2 pk"),
        (
            "https://www.walmart.com/ip/Great-Value-Whole-Milk-Gallon/10450114",
            "Great Value Whole Milk Gallon",
        ),
        ("https://example.com/products/olive%20oil", "olive oil"),
        ("https://example.com/12345/67890", "example.com"),
        ("https://www.example.com", "example.com"),
        ("https://example.com/a_b+c", "a b c"),
    ],
)
def test_name_from_url(url, name):
    assert name_from_url(url) == name


def test_name_from_url_never_empty_and_capped():
    assert name_from_url("https://x.io/" + "a-" * 400) != ""
    assert len(name_from_url("https://x.io/" + "a-" * 400)) <= 255


# ── resolve_link_preview (title + image) ─────────────────────────────────────


def _client_returning(body: str, status_code: int = 200) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        transport=httpx.MockTransport(lambda req: httpx.Response(status_code, text=body))
    )


PRODUCT_PAGE = """
<html><head>
<title>Haakaa Ladybug Milk Collectors | Meijer</title>
<meta property="og:title" content="OG Ladybug Collectors" />
<meta property="og:image" content="https://cdn.meijer.com/og/haakaa.jpg" />
<script type="application/ld+json">
{"@graph": [{"@type": "WebSite", "name": "Meijer"},
            {"@type": ["Product", "Thing"], "name": "Haakaa Ladybug Breast Milk Collectors, 2 pk",
             "image": ["https://cdn.meijer.com/jsonld/haakaa.jpg"]}]}
</script>
</head><body></body></html>
"""


async def test_preview_prefers_jsonld_product():
    p = await resolve_link_preview(MEIJER_URL, client=_client_returning(PRODUCT_PAGE))
    assert p.title == "Haakaa Ladybug Breast Milk Collectors, 2 pk"
    assert p.image_url == "https://cdn.meijer.com/jsonld/haakaa.jpg"


async def test_preview_falls_back_to_og_title():
    page = (
        '<head><meta content="OG Name &amp; Co" property="og:title"><title>Doc Title</title></head>'
    )
    assert (await resolve_link_preview(MEIJER_URL, client=_client_returning(page))).title == (
        "OG Name & Co"
    )


async def test_preview_falls_back_to_html_title_and_strips_site_suffix():
    page = "<head><title>  Whole \n Milk – Walmart.com </title></head>"
    assert (await resolve_link_preview(MEIJER_URL, client=_client_returning(page))).title == (
        "Whole Milk"
    )


async def test_preview_image_from_og_and_rejects_non_http():
    og = '<head><meta property="og:image" content="https://cdn.example.com/p.png"></head>'
    assert (await resolve_link_preview(MEIJER_URL, client=_client_returning(og))).image_url == (
        "https://cdn.example.com/p.png"
    )
    data_uri = '<head><meta property="og:image" content="data:image/png;base64,AAAA"></head>'
    assert (
        await resolve_link_preview(MEIJER_URL, client=_client_returning(data_uri))
    ).image_url is None


async def test_preview_empty_on_http_error_or_empty():
    p = await resolve_link_preview(MEIJER_URL, client=_client_returning("x", 404))
    assert p.title is None and p.image_url is None
    p = await resolve_link_preview(MEIJER_URL, client=_client_returning("<p>nothing</p>"))
    assert p.title is None and p.image_url is None

    def boom(req):
        raise httpx.ConnectError("nope", request=req)

    client = httpx.AsyncClient(transport=httpx.MockTransport(boom))
    assert (await resolve_link_preview(MEIJER_URL, client=client)).title is None


async def test_preview_never_fetches_private_hosts():
    def fail(req):  # pragma: no cover - reaching this is the failure
        raise AssertionError("private URL must not be fetched")

    client = httpx.AsyncClient(transport=httpx.MockTransport(fail))
    for url in ("http://localhost/x", "http://192.168.1.10/x", "http://10.0.0.2/x", "ftp://x/y"):
        p = await resolve_link_preview(url, client=client)
        assert p.title is None and p.image_url is None


# ── Endpoints ────────────────────────────────────────────────────────────────


async def _default_list(client):
    resp = await client.get("/lists/default")
    assert resp.status_code == 200, resp.text
    return resp.json()


def _mock_title(monkeypatch, title, image_url=None):
    async def fake(url, *, client=None):
        return LinkPreview(title=title, image_url=image_url)

    monkeypatch.setattr("app.services.shopping_service.resolve_link_preview", fake)


async def test_long_plain_name_is_422_not_500(auth_client):
    """THE regression: a >255-char non-URL name must be a clean validation error."""
    lst = await _default_list(auth_client)
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "x" * 320})
    assert resp.status_code == 422
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "x" * 3000})
    assert resp.status_code == 422


async def test_long_name_patch_is_422_not_500(auth_client):
    lst = await _default_list(auth_client)
    lst = (await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "milk"})).json()
    item = lst["items"][0]
    resp = await auth_client.patch(
        f"/lists/{lst['id']}/items/{item['id']}", json={"name": "x" * 320}
    )
    assert resp.status_code == 422


async def test_url_only_add_uses_fetched_title(auth_client, monkeypatch):
    _mock_title(monkeypatch, "Great Value Whole Milk, Gallon")
    lst = await _default_list(auth_client)
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": MEIJER_URL})
    assert resp.status_code == 201, resp.text
    (item,) = resp.json()["items"]
    assert item["name"] == "Great Value Whole Milk, Gallon"
    assert item["link_url"] == MEIJER_URL
    # Category comes from the cleaned name ("milk" ⇒ dairy), never the URL slug.
    assert item["category"] == "dairy"


async def test_url_only_add_falls_back_to_slug_name(auth_client, monkeypatch):
    _mock_title(monkeypatch, None)
    lst = await _default_list(auth_client)
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": MEIJER_URL})
    assert resp.status_code == 201, resp.text
    (item,) = resp.json()["items"]
    assert item["name"] == "haakaa ladybug breast milk collectors 2 5 oz 2 pk"
    assert item["link_url"] == MEIJER_URL


async def test_url_only_add_never_pollutes_history(auth_client, monkeypatch):
    _mock_title(monkeypatch, "Great Value Whole Milk, Gallon")
    lst = await _default_list(auth_client)
    await auth_client.post(f"/lists/{lst['id']}/items", json={"name": MEIJER_URL})
    for q in ("great", "haakaa", "meijer"):
        hits = (await auth_client.get("/lists/suggest", params={"q": q})).json()
        assert hits == [], q


async def test_typed_text_plus_url_keeps_typed_name(auth_client, monkeypatch):
    _mock_title(monkeypatch, "SHOULD NOT BE USED")
    lst = await _default_list(auth_client)
    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": f"milk collector {MEIJER_URL}"}
    )
    assert resp.status_code == 201, resp.text
    (item,) = resp.json()["items"]
    assert item["name"] == "milk collector"
    assert item["link_url"] == MEIJER_URL
    # The typed text (the user's vocabulary) does feed autocomplete.
    hits = (await auth_client.get("/lists/suggest", params={"q": "milk col"})).json()
    assert [h["name"] for h in hits] == ["milk collector"]


async def test_link_merges_by_name_first_link_wins(auth_client, monkeypatch):
    _mock_title(monkeypatch, None)
    lst = await _default_list(auth_client)
    await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "milk"})
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": f"milk {MEIJER_URL}"})
    items = resp.json()["items"]
    assert len(items) == 1
    assert items[0]["link_url"] == MEIJER_URL
    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "milk https://other.example.com/milk"}
    )
    (item,) = resp.json()["items"]
    assert item["link_url"] == MEIJER_URL  # first link wins; a merge doesn't churn it


async def test_patch_link_url_set_clear_and_reject(auth_client):
    lst = await _default_list(auth_client)
    lst = (await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "milk"})).json()
    item = lst["items"][0]
    url = f"/lists/{lst['id']}/items/{item['id']}"

    resp = await auth_client.patch(url, json={"link_url": "https://example.com/milk"})
    assert resp.json()["items"][0]["link_url"] == "https://example.com/milk"

    resp = await auth_client.patch(url, json={"link_url": ""})
    assert resp.json()["items"][0]["link_url"] is None

    resp = await auth_client.patch(url, json={"link_url": "javascript:alert(1)"})
    assert resp.status_code == 422


# ── v0.6: thumbnails + "buy again" recall ────────────────────────────────────


async def test_url_only_add_stores_thumbnail(auth_client, monkeypatch):
    _mock_title(monkeypatch, "Whole Milk", image_url="https://cdn.example.com/milk.jpg")
    lst = await _default_list(auth_client)
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": MEIJER_URL})
    (item,) = resp.json()["items"]
    assert item["image_url"] == "https://cdn.example.com/milk.jpg"


async def test_typed_plus_url_add_still_gets_a_thumbnail(auth_client, monkeypatch):
    # Even when the user types their own name, the one fetch grabs the picture.
    _mock_title(monkeypatch, "unused title", image_url="https://cdn.example.com/x.jpg")
    lst = await _default_list(auth_client)
    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": f"milk collector {MEIJER_URL}"}
    )
    (item,) = resp.json()["items"]
    assert item["name"] == "milk collector"
    assert item["image_url"] == "https://cdn.example.com/x.jpg"


async def test_buy_again_reattaches_link_and_thumbnail(auth_client, monkeypatch):
    _mock_title(monkeypatch, "unused", image_url="https://cdn.example.com/x.jpg")
    lst = await _default_list(auth_client)
    lid = lst["id"]
    # Add "milk collector <url>", then clear the whole list.
    await auth_client.post(f"/lists/{lid}/items", json={"name": f"milk collector {MEIJER_URL}"})
    got = (await auth_client.get(f"/lists/{lid}")).json()
    await auth_client.delete(f"/lists/{lid}/items/{got['items'][0]['id']}")

    # Re-add by name only — no URL pasted. Buy-again re-attaches the link + thumbnail.
    resp = await auth_client.post(f"/lists/{lid}/items", json={"name": "milk collector"})
    (item,) = resp.json()["items"]
    assert item["link_url"] == MEIJER_URL
    assert item["image_url"] == "https://cdn.example.com/x.jpg"


async def test_buy_again_only_for_typed_names_not_url_derived(auth_client, monkeypatch):
    # A URL-only add's fetched title must not become a recall key (no history pollution).
    _mock_title(monkeypatch, "Great Value Whole Milk", image_url="https://cdn.example.com/x.jpg")
    lst = await _default_list(auth_client)
    lid = lst["id"]
    await auth_client.post(f"/lists/{lid}/items", json={"name": MEIJER_URL})
    got = (await auth_client.get(f"/lists/{lid}")).json()
    await auth_client.delete(f"/lists/{lid}/items/{got['items'][0]['id']}")

    # Typing the product's fetched title later must NOT recall a link (it was never a typed name).
    resp = await auth_client.post(f"/lists/{lid}/items", json={"name": "Great Value Whole Milk"})
    (item,) = resp.json()["items"]
    assert item["link_url"] is None


async def test_patch_clearing_link_drops_thumbnail(auth_client, monkeypatch):
    _mock_title(monkeypatch, "milk", image_url="https://cdn.example.com/x.jpg")
    lst = await _default_list(auth_client)
    lid = lst["id"]
    resp = await auth_client.post(f"/lists/{lid}/items", json={"name": f"milk {MEIJER_URL}"})
    item = resp.json()["items"][0]
    assert item["image_url"] == "https://cdn.example.com/x.jpg"

    resp = await auth_client.patch(f"/lists/{lid}/items/{item['id']}", json={"link_url": ""})
    cleared = resp.json()["items"][0]
    assert cleared["link_url"] is None and cleared["image_url"] is None
