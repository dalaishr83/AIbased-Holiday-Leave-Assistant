/**
 * holiday_agent.js — Vacation Planner Assistant SPA (compiled from TypeScript)
 * All client-side logic in a single IIFE.
 */
(function () {
    "use strict";

    // ── State ──────────────────────────────────────────────────────────────────
    let employees = [];
    let currentYear = new Date().getFullYear();
    let isThinking = false;

    // ── DOM refs ───────────────────────────────────────────────────────────────
    const messagesEl      = document.getElementById("messages");
    const welcomeCard     = document.getElementById("welcomeCard");
    const messageInput    = document.getElementById("messageInput");
    const sendBtn         = document.getElementById("sendBtn");
    const fileInput       = document.getElementById("fileInput");
    const uploadZone      = document.getElementById("uploadZone");
    const fileList        = document.getElementById("fileList");
    const yearSelect      = document.getElementById("yearSelect");
    const employeeList    = document.getElementById("employeeList");
    const quickChips      = document.getElementById("quickChips");
    const clearHistoryBtn = document.getElementById("clearHistoryBtn");
    const refreshBtn      = document.getElementById("refreshBtn");
    const newChatBtn      = document.getElementById("newChatBtn");
    const hamburgerBtn    = document.getElementById("hamburgerBtn");
    const sidebar         = document.getElementById("sidebar");
    const overlay         = document.getElementById("overlay");
    const statusDot       = document.getElementById("statusDot");
    const topbarSubtitle  = document.getElementById("topbarSubtitle");

    // ── Boot sequence ──────────────────────────────────────────────────────────
    Promise.all([fetchEmployees(), fetchYears(), fetchFiles()]).catch(() => {});

    // ── Chat ───────────────────────────────────────────────────────────────────

    async function sendMessage() {
        const msg = messageInput.value.trim();
        if (!msg || isThinking) return;

        appendMessage(msg, "user");
        messageInput.value = "";
        autoResizeTextarea();
        setThinking(true);
        const thinking = appendThinking();

        try {
            const res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: msg }),
            });
            const data = await res.json();
            thinking.remove();
            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
            } else {
                appendMessage(data.reply, "bot", data.type);
            }
        } catch (e) {
            thinking.remove();
            appendMessage("⚠️ Network error. Please try again.", "bot");
        } finally {
            setThinking(false);
            messageInput.focus();
        }
    }

    // ── Message rendering ──────────────────────────────────────────────────────

    function hideWelcomeCard() {
        if (welcomeCard && welcomeCard.parentNode) welcomeCard.remove();
    }

    function showWelcomeCard() {
        if (!document.getElementById("welcomeCard")) {
            const greetingName = messagesEl.getAttribute("data-greeting-name") || "there";
            const card = document.createElement("div");
            card.id = "welcomeCard";
            card.className = "welcome-card";
            card.innerHTML = messagesEl.querySelector ? "" : "";
            // Re-insert the original welcome card markup
            card.innerHTML =
                '<div class="welcome-robot">' +
                '<svg width="120" height="130" viewBox="0 0 120 130" fill="none">' +
                '<g class="wc-robot-group">' +
                '<rect x="58" y="4" width="4" height="18" rx="2" fill="#3b5bdb"/>' +
                '<circle class="wc-antenna-tip" cx="60" cy="4" r="5" fill="#60a5fa"/>' +
                '<rect x="26" y="20" width="68" height="48" rx="14" fill="#3b5bdb"/>' +
                '<rect class="wc-eye" x="36" y="34" width="18" height="18" rx="5" fill="#fff"/>' +
                '<rect class="wc-eye" x="66" y="34" width="18" height="18" rx="5" fill="#fff"/>' +
                '<circle cx="45" cy="43" r="6" fill="#1e3a8a"/>' +
                '<circle cx="75" cy="43" r="6" fill="#1e3a8a"/>' +
                '<circle cx="47" cy="40" r="2.5" fill="#93c5fd"/>' +
                '<circle cx="77" cy="40" r="2.5" fill="#93c5fd"/>' +
                '<rect class="wc-mouth" x="42" y="56" width="36" height="6" rx="3" fill="#93c5fd"/>' +
                '<rect x="54" y="68" width="12" height="8" rx="3" fill="#2952cc"/>' +
                '<rect x="18" y="76" width="84" height="46" rx="14" fill="#3b5bdb"/>' +
                '<circle cx="42" cy="99" r="6" fill="#1e3a8a"/>' +
                '<circle cx="60" cy="99" r="6" fill="#60a5fa"/>' +
                '<circle cx="78" cy="99" r="6" fill="#1e3a8a"/>' +
                '<rect x="32" y="112" width="56" height="4" rx="2" fill="#2952cc"/>' +
                '<rect class="wc-arm-l" x="2" y="78" width="16" height="32" rx="8" fill="#2952cc"/>' +
                '<rect class="wc-arm-r" x="102" y="78" width="16" height="32" rx="8" fill="#2952cc"/>' +
                '<rect x="32" y="122" width="20" height="8" rx="4" fill="#2952cc"/>' +
                '<rect x="68" y="122" width="20" height="8" rx="4" fill="#2952cc"/>' +
                '</g></svg></div>' +
                '<h2 class="welcome-title">Hello, ' + greetingName + '! I\'m your Leave Assistant.</h2>' +
                '<p class="welcome-sub">Ask me anything about employee holidays and leave—I\'ll answer strictly based<br>' +
                'on your Excel data. You can also add new leave entries or delete existing ones.<br>' +
                'Contact your administrator to upload or manage files.</p>';
            messagesEl.insertBefore(card, messagesEl.firstChild);
        }
    }

    // Inline robot SVG reused for every bot avatar
    const BOT_AVATAR_SVG =
        '<svg width="20" height="20" viewBox="0 0 100 100" fill="none">' +
        '<rect x="28" y="30" width="44" height="34" rx="8" fill="white" fill-opacity="0.95"/>' +
        '<rect x="36" y="39" width="10" height="10" rx="3" fill="#2563eb"/>' +
        '<rect x="54" y="39" width="10" height="10" rx="3" fill="#2563eb"/>' +
        '<rect x="40" y="54" width="20" height="4" rx="2" fill="#93c5fd"/>' +
        '<rect x="46" y="20" width="8" height="10" rx="3" fill="white" fill-opacity="0.9"/>' +
        '<circle cx="50" cy="17" r="4" fill="#bfdbfe"/>' +
        '<rect x="18" y="38" width="10" height="18" rx="5" fill="white" fill-opacity="0.8"/>' +
        '<rect x="72" y="38" width="10" height="18" rx="5" fill="white" fill-opacity="0.8"/>' +
        '<rect x="32" y="64" width="12" height="16" rx="5" fill="white" fill-opacity="0.8"/>' +
        '<rect x="56" y="64" width="12" height="16" rx="5" fill="white" fill-opacity="0.8"/>' +
        '</svg>';

    function makeBotAvatar() {
        const av = document.createElement("div");
        av.className = "bot-avatar";
        av.innerHTML = BOT_AVATAR_SVG;
        return av;
    }

    function appendMessage(text, role, type = "text") {
        hideWelcomeCard();
        const div = document.createElement("div");
        div.className = "message " + role;
        if (type === "vacation_prompt") div.classList.add("vacation-prompt");

        // Add avatar for bot messages
        if (role === "bot") div.appendChild(makeBotAvatar());

        const bubble = document.createElement("div");
        bubble.className = "bubble";

        if (type === "report") {
            const match = text.match(/report-file:\s*(.+)/i);
            const display = text.replace(/\nreport-file:[^\n]*/i, "").trim();
            bubble.innerHTML = renderMarkdown(display);
            if (match) {
                const fname = match[1].trim().split(/[/\\]/).pop() || "";
                const link = document.createElement("a");
                link.href = "/api/reports/" + fname;
                link.target = "_blank";
                link.className = "report-link";
                link.textContent = "Open HTML Report";
                bubble.appendChild(link);
            }
        } else {
            bubble.innerHTML = renderMarkdown(text);
        }

        div.appendChild(bubble);
        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        return div;
    }

    function appendThinking() {
        hideWelcomeCard();
        // Show "Thinking…" in topbar subtitle
        if (topbarSubtitle) topbarSubtitle.textContent = "Thinking…";

        const div = document.createElement("div");
        div.className = "message bot";
        div.id = "thinkingRow";
        div.appendChild(makeBotAvatar());

        const dots = document.createElement("div");
        dots.className = "thinking-dots";
        dots.innerHTML = "<span></span><span></span><span></span>";
        div.appendChild(dots);

        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        return div;
    }

    function renderMarkdown(text) {
        let html = text.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
        html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
        html = html.replace(/\n/g, "<br>");
        return html;
    }

    // ── File upload ────────────────────────────────────────────────────────────

    async function uploadFile(file) {
        if (!file.name.toLowerCase().endsWith(".xlsx")) {
            appendMessage("⚠️ Only .xlsx files are supported.", "bot");
            return;
        }
        const thinking = appendMessage("Uploading and parsing file…", "bot");
        setThinking(true);

        const formData = new FormData();
        formData.append("file", file);

        try {
            const res = await fetch("/api/upload", { method: "POST", body: formData });
            const data = await res.json();
            thinking.remove();
            setThinking(false);

            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
                return;
            }

            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            buildQuickChips();
            await fetchYears();
            appendMessage("✅ " + data.message + "\n\nYou can now ask questions about employee leave data.", "bot");
        } catch (e) {
            thinking.remove();
            setThinking(false);
            appendMessage("⚠️ Upload failed. Please try again.", "bot");
        }
    }

    // ── API helpers ────────────────────────────────────────────────────────────

    async function fetchEmployees() {
        try {
            const res = await fetch("/api/employees");
            const data = await res.json();
            employees = data.employees || [];
            renderEmployees();
            buildQuickChips();
        } catch {}
    }

    async function fetchYears() {
        try {
            const res = await fetch("/api/years");
            const data = await res.json();
            const years = data.years || [];
            renderYearSelector(years);
        } catch {}
    }

    async function fetchFiles() {
        try {
            const res = await fetch("/api/files");
            const data = await res.json();
            renderFiles(data.files || []);
        } catch {}
    }

    async function switchFile(path) {
        setThinking(true);
        try {
            const res = await fetch("/api/switch-file", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ path }),
            });
            const data = await res.json();
            if (data.error) { appendMessage("⚠️ " + data.error, "bot"); return; }
            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            buildQuickChips();
            await fetchYears();
            appendMessage("Switched to " + path.split(/[/\\]/).pop() + ". " + employees.length + " employees loaded.", "bot");
        } catch {
            appendMessage("⚠️ Failed to switch file.", "bot");
        } finally {
            setThinking(false);
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    function renderEmployees() {
        if (!employeeList) return;
        employeeList.innerHTML = "";
        employees.forEach(name => {
            const li = document.createElement("li");
            // Blue dot indicator
            const dot = document.createElement("span");
            dot.className = "emp-dot";
            li.appendChild(dot);
            const label = document.createElement("span");
            label.textContent = name;
            li.appendChild(label);
            li.title = name;
            li.addEventListener("click", () => {
                messageInput.value = "How many days has " + name + " taken in " + currentYear + "?";
                messageInput.focus();
            });
            employeeList.appendChild(li);
        });
        // Update topbar subtitle
        if (topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? employees.length + " employee(s) loaded"
                : "No file loaded";
        }
    }

    function renderFiles(files) {
        if (!fileList) return;
        fileList.innerHTML = "";
        files.forEach(f => {
            const li = document.createElement("li");
            const nameSpan = document.createElement("span");
            nameSpan.className = "file-name";
            nameSpan.textContent = f.name;
            li.appendChild(nameSpan);
            if (f.active) {
                li.classList.add("active");
                const badge = document.createElement("span");
                badge.className = "file-badge";
                badge.textContent = "Active";
                li.appendChild(badge);
            }
            li.title = f.path;
            li.addEventListener("click", () => switchFile(f.path));
            fileList.appendChild(li);
        });
    }

    function renderYearSelector(years) {
        if (!yearSelect) return;
        yearSelect.innerHTML = "";
        if (years.length === 0) {
            const opt = document.createElement("option");
            opt.value = String(currentYear);
            opt.textContent = String(currentYear);
            yearSelect.appendChild(opt);
            return;
        }
        years.forEach(y => {
            const opt = document.createElement("option");
            opt.value = String(y);
            opt.textContent = String(y);
            yearSelect.appendChild(opt);
        });
        currentYear = years[0];
        buildQuickChips();
    }

    function buildQuickChips() {
        if (!quickChips) return;
        quickChips.innerHTML = "";
        const loginUsername = (messagesEl
            ? messagesEl.getAttribute("data-login-username")
            : null) || employees[0] || "me";
        const templates = [
            // ── Full-year (Rule 5) ────────────────────────────────────────────
            "Show leave summary for {name} in {year}",
            "How many leave days does {name} have in {year}?",
            "What is {name}'s remaining leave for {year}?",
            "What is {name}'s leave utilization rate in {year}?",
            "Break down {name}'s leave types for {year}",
            "What is {name}'s longest leave streak in {year}?",
            // ── Single-month generic (Rule 3) ─────────────────────────────────
            "How many leave days does {name} have in March {year}?",
            "How many days did {name} take in April {year}?",
            // ── Single-month type-specific (Rule 4) ───────────────────────────
            "How many V leave days does {name} have in March {year}?",
            "How many PC leave days does {name} have in April {year}?",
            "How many Public Holiday days does {name} have in January {year}?",
            // ── Date query ────────────────────────────────────────────────────
            "Who is on leave on 15 March {year}?",
            // ── Range generic (Rule 1) ────────────────────────────────────────
            "How many days does {name} have from January to March {year}?",
            "How many leave days does {name} have from April to June {year}?",
            // ── Range type-specific (Rule 2) ──────────────────────────────────
            "How many V leave days does {name} have from January to March {year}?",
            "How many PC leave days does {name} have from January to June {year}?",
            "How many Public Holiday days does {name} have from January to March {year}?",
            // ── All-employees ─────────────────────────────────────────────────
            "Which employees have the most leave in {year}?",
            "Show all employees' leave totals for {year}",
            // ── Actions ───────────────────────────────────────────────────────
            "Generate leave report for {name} in {year}",
            "Add vacation for {name}",
            "Delete vacation for {name}",
        ];
        templates.forEach(tpl => {
            const label = tpl.replace("{name}", loginUsername).replace("{year}", String(currentYear));
            const chip = document.createElement("button");
            chip.className = "chip";
            chip.textContent = label;
            chip.addEventListener("click", () => {
                messageInput.value = label;
                messageInput.focus();
            });
            quickChips.appendChild(chip);
        });
    }

    function setThinking(state) {
        isThinking = state;
        sendBtn.disabled = state;
        messageInput.disabled = state;
        // Restore topbar subtitle when done thinking
        if (!state && topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? employees.length + " employee(s) loaded"
                : "No file loaded";
        }
    }

    function autoResizeTextarea() {
        messageInput.style.height = "auto";
        messageInput.style.height = Math.min(messageInput.scrollHeight, 160) + "px";
    }

    // ── Event listeners ────────────────────────────────────────────────────────

    sendBtn.addEventListener("click", sendMessage);

    messageInput.addEventListener("keydown", e => {
        if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });
    messageInput.addEventListener("input", autoResizeTextarea);

    if (uploadZone && fileInput) {
        uploadZone.addEventListener("click", () => fileInput.click());
        fileInput.addEventListener("change", () => {
            if (fileInput.files && fileInput.files[0]) uploadFile(fileInput.files[0]);
            fileInput.value = "";
        });

        uploadZone.addEventListener("dragover", e => { e.preventDefault(); uploadZone.classList.add("drag-over"); });
        uploadZone.addEventListener("dragleave", () => uploadZone.classList.remove("drag-over"));
        uploadZone.addEventListener("drop", e => {
            e.preventDefault();
            uploadZone.classList.remove("drag-over");
            const file = e.dataTransfer && e.dataTransfer.files[0];
            if (file) uploadFile(file);
        });
    }

    if (yearSelect) {
        yearSelect.addEventListener("change", () => {
            currentYear = parseInt(yearSelect.value, 10);
            buildQuickChips();
        });
    }

    clearHistoryBtn.addEventListener("click", async () => {
        await fetch("/api/clear-history", { method: "POST" });
        messagesEl.innerHTML = "";
    });

    refreshBtn.addEventListener("click", () => {
        fetchEmployees(); fetchFiles(); fetchYears();
    });

    newChatBtn.addEventListener("click", async () => {
        await fetch("/api/clear-history", { method: "POST" });
        messagesEl.innerHTML = "";
        showWelcomeCard();
    });

    hamburgerBtn.addEventListener("click", () => {
        sidebar.classList.add("open");
        overlay.classList.add("visible");
    });

    overlay.addEventListener("click", closeSidebar);

    function closeSidebar() {
        sidebar.classList.remove("open");
        overlay.classList.remove("visible");
    }

    // Swipe-left to close sidebar on mobile
    let touchStartX = 0;
    sidebar.addEventListener("touchstart", e => { touchStartX = e.touches[0].clientX; }, { passive: true });
    sidebar.addEventListener("touchend", e => {
        if (e.changedTouches[0].clientX - touchStartX < -40) closeSidebar();
    }, { passive: true });

    // Collapsible sections — "fileListContent" starts collapsed so employees are visible on load
    document.querySelectorAll(".collapsible").forEach(header => {
        const target = header.dataset["target"];
        if (!target) return;
        const content = document.getElementById(target);
        if (!content) return;
        if (target === "fileListContent") {
            content.classList.add("collapsed");
        } else {
            header.classList.add("open");
        }
        header.addEventListener("click", () => {
            const isOpen = header.classList.toggle("open");
            content.classList.toggle("collapsed", !isOpen);
        });
    });

    statusDot.classList.add("online");

})();

/* ── Mobile topbar adaptation — New Chat button ─────────────────────────── */
(function () {
    var newChatBtnEl = document.getElementById('newChatBtn');
    if (!newChatBtnEl) return;
    var textEl = newChatBtnEl.querySelector('.topbar-btn-text');
    var iconEl = newChatBtnEl.querySelector('.topbar-btn-icon');
    function adaptTopbar() {
        var isMobile = window.innerWidth <= 768;
        if (textEl) textEl.style.display = isMobile ? 'none' : '';
        if (iconEl) iconEl.style.display = isMobile ? '' : 'none';
    }
    adaptTopbar();
    window.addEventListener('resize', adaptTopbar);
})();
