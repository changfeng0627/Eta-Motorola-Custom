package fuck.andes.agent.browser

/**
 * JavaScript code strings injected into WebView pages.
 * Includes reading readable text, reading text, finding elements, clicking, typing, scrolling, page info, etc.
 */
internal object BrowserDomScripts {

    /**
     * Read all visible/readable text from the page.
     */
    fun readable(selector: String = "*"): String = """
        (function() {
            var elements = document.querySelectorAll('${selector.replace("'", "\\'")}');
            var texts = [];
            for (var i = 0; i < elements.length; i++) {
                var el = elements[i];
                if (el.offsetParent !== null && el.textContent.trim()) {
                    var tag = el.tagName.toLowerCase();
                    if (['script', 'style', 'noscript', 'iframe'].indexOf(tag) === -1) {
                        var text = el.textContent.trim();
                        if (text) {
                            texts.push(text);
                        }
                    }
                }
            }
            return texts.join('\n');
        })();
    """.trimIndent()

    /**
     * Read text from a specific element or all elements.
     */
    fun text(selector: String = "body"): String = """
        (function() {
            var element = document.querySelector('${selector.replace("'", "\\'")}');
            if (element) {
                return element.innerText || element.textContent || '';
            }
            return '';
        })();
    """.trimIndent()

    /**
     * Find elements matching a selector.
     */
    fun findElements(selector: String): String = """
        (function() {
            var elements = document.querySelectorAll('${selector.replace("'", "\\'")}');
            var results = [];
            for (var i = 0; i < elements.length; i++) {
                var el = elements[i];
                var rect = el.getBoundingClientRect();
                results.push({
                    index: i,
                    tag: el.tagName.toLowerCase(),
                    text: el.innerText || el.textContent || '',
                    rect: {
                        x: rect.x,
                        y: rect.y,
                        width: rect.width,
                        height: rect.height
                    },
                    attributes: el.outerHTML.substring(0, 200)
                });
            }
            return JSON.stringify(results);
        })();
    """.trimIndent()

    /**
     * Click an element.
     */
    fun click(selector: String): String = """
        (function() {
            var element = document.querySelector('${selector.replace("'", "\\'")}');
            if (element) {
                element.click();
                return 'clicked';
            }
            return 'not found';
        })();
    """.trimIndent()

    /**
     * Type text into an input/textarea element.
     */
    fun type(selector: String, text: String): String = """
        (function() {
            var element = document.querySelector('${selector.replace("'", "\\'")}');
            if (element) {
                element.focus();
                element.value = '${text.replace("'", "\\'").replace("\n", "\\n")}';
                element.dispatchEvent(new Event('input', { bubbles: true }));
                element.dispatchEvent(new Event('change', { bubbles: true }));
                return 'typed';
            }
            return 'not found';
        })();
    """.trimIndent()

    /**
     * Scroll the page.
     */
    fun scroll(x: Int, y: Int): String = """
        (function() {
            window.scrollTo($x, $y);
            return 'scrolled';
        })();
    """.trimIndent()

    /**
     * Get page information.
     */
    fun pageInfo(): String = """
        (function() {
            return JSON.stringify({
                title: document.title,
                url: window.location.href,
                readyState: document.readyState,
                charset: document.characterSet,
                bodyTextLength: document.body.innerText.length,
                images: document.images.length,
                links: document.links.length,
                forms: document.forms.length
            });
        })();
    """.trimIndent()

    /**
     * Get element at specific coordinates.
     */
    fun elementFromPoint(x: Int, y: Int): String = """
        (function() {
            var element = document.elementFromPoint($x, $y);
            if (element) {
                var rect = element.getBoundingClientRect();
                return JSON.stringify({
                    tag: element.tagName.toLowerCase(),
                    text: element.innerText || element.textContent || '',
                    rect: {
                        x: rect.x,
                        y: rect.y,
                        width: rect.width,
                        height: rect.height
                    }
                });
            }
            return 'null';
        })();
    """.trimIndent()

    /**
     * Get the bounding rect of an element.
     */
    fun getBoundingClientRect(selector: String): String = """
        (function() {
            var element = document.querySelector('${selector.replace("'", "\\'")}');
            if (element) {
                var rect = element.getBoundingClientRect();
                return JSON.stringify({
                    x: rect.x,
                    y: rect.y,
                    width: rect.width,
                    height: rect.height
                });
            }
            return 'null';
        })();
    """.trimIndent()
}
