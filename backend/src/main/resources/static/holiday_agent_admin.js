/**
 * holiday_agent_admin.js — Admin page SPA logic.
 * Extends the base functionality with file deletion support.
 */
(function () {
    "use strict";

    // ── State ──────────────────────────────────────────────────────────────────
    let employees = [];
    let currentYear = new Date().getFullYear();
    let isThinking = false;

    // ── DOM refs (null-safe — admin page may omit some elements) ───────────────
    const messagesEl      = document.getElementById("messages");
    const welcomeCard     = document.getElementById("welcomeCard");
    const messageInput    = document.getElementById("messageInput");
    const sendBtn         = document.getElementById("sendBtn");
    const fileInput       = document.getElementById("fileInput");
    const uploadZone      = document.getElementById("uploadZone");
    const fileList        = document.getElementById("fileList");
    const employeeList    = document.getElementById("employeeList");
    const clearHistoryBtn = document.getElementById("clearHistoryBtn");
    const refreshBtn      = document.getElementById("refreshBtn");
    const newChatBtn      = document.getElementById("newChatBtn");
    const hamburgerBtn    = document.getElementById("hamburgerBtn");
    const sidebar         = document.getElementById("sidebar");
    const overlay         = document.getElementById("overlay");
    const statusDot       = document.getElementById("statusDot");
    const topbarSubtitle  = document.getElementById("topbarSubtitle");

    // ── Boot sequence ──────────────────────────────────────────────────────────
    Promise.all([fetchEmployees(), fetchFiles()]).catch(function () {});

    // ── Chat ───────────────────────────────────────────────────────────────────

    async function sendMessage() {
        var msg = messageInput.value.trim();
        if (!msg || isThinking) return;
        appendMessage(msg, "user");
        messageInput.value = "";
        autoResizeTextarea();
        setThinking(true);
        var thinking = appendThinking();
        try {
            var res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: msg })
            });
            var data = await res.json();
            thinking.remove();
            appendMessage(data.error ? "⚠️ " + data.error : data.reply, "bot", data.type);
        } catch (e) {
            thinking.remove();
            appendMessage("⚠️ Network error. Please try again.", "bot");
        } finally {
            setThinking(false);
            messageInput.focus();
        }
    }

    // ── File upload ────────────────────────────────────────────────────────────

    async function uploadFile(file) {
        if (!file.name.toLowerCase().endsWith(".xlsx")) {
            appendMessage("⚠️ Only .xlsx files are supported.", "bot");
            return;
        }
        var thinking = appendMessage("Uploading and parsing file…", "bot");
        setThinking(true);
        var formData = new FormData();
        formData.append("file", file);
        try {
            var res = await fetch("/api/upload", { method: "POST", body: formData });
            var data = await res.json();
            thinking.remove();
            setThinking(false);
            if (data.error) { appendMessage("⚠️ " + data.error, "bot"); return; }
            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            appendMessage("✅ " + data.message + "\n\nYou can now ask questions about employee leave data.", "bot");
        } catch (e) {
            thinking.remove();
            setThinking(false);
            appendMessage("⚠️ Upload failed. Please try again.", "bot");
        }
    }

    // ── File deletion (admin only) ─────────────────────────────────────────────

    async function deleteFile(filename) {
        if (!confirm("Delete '" + filename + "'? This will remove the master, working, and upload copies.")) return;
        try {
            var res = await fetch("/api/admin/files", {
                method: "DELETE",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ filename: filename })
            });
            var data = await res.json();
            if (data.error) {
                appendMessage("⚠️ " + data.error, "bot");
            } else {
                renderFiles(data.files || []);
                employees = [];
                renderEmployees();
                appendMessage("✅ " + data.message, "bot");
            }
        } catch (e) {
            appendMessage("⚠️ Delete failed. Please try again.", "bot");
        }
    }

    // ── API helpers ────────────────────────────────────────────────────────────

    async function fetchEmployees() {
        try {
            var res = await fetch("/api/employees");
            var data = await res.json();
            employees = data.employees || [];
            renderEmployees();
        } catch (e) {}
    }

    async function fetchFiles() {
        try {
            var res = await fetch("/api/files");
            var data = await res.json();
            renderFiles(data.files || []);
        } catch (e) {}
    }

    async function switchFile(path) {
        setThinking(true);
        try {
            var res = await fetch("/api/switch-file", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ path: path })
            });
            var data = await res.json();
            if (data.error) { appendMessage("⚠️ " + data.error, "bot"); return; }
            employees = data.employees || [];
            renderEmployees();
            renderFiles(data.files || []);
            appendMessage("Switched to " + path.split(/[/\\]/).pop() + ". " + employees.length + " employees loaded.", "bot");
        } catch (e) {
            appendMessage("⚠️ Failed to switch file.", "bot");
        } finally {
            setThinking(false);
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    function renderEmployees() {
        if (topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? "Admin — " + employees.length + " employee(s) loaded"
                : "Admin — No file loaded";
        }
        if (!employeeList) return;
        employeeList.innerHTML = "";
        employees.forEach(function (name) {
            var li = document.createElement("li");
            var dot = document.createElement("span");
            dot.className = "emp-dot";
            li.appendChild(dot);
            var label = document.createElement("span");
            label.textContent = name;
            li.appendChild(label);
            li.title = name;
            employeeList.appendChild(li);
        });
    }

    function renderFiles(files) {
        if (!fileList) return;
        fileList.innerHTML = "";
        files.forEach(function (f) {
            var li = document.createElement("li");
            li.style.display = "flex";
            li.style.alignItems = "center";

            var nameSpan = document.createElement("span");
            nameSpan.className = "file-name";
            nameSpan.textContent = f.name;
            nameSpan.style.cursor = "pointer";
            nameSpan.title = f.path;
            nameSpan.addEventListener("click", function () { switchFile(f.path); });
            li.appendChild(nameSpan);

            if (f.active) {
                li.classList.add("active");
                var badge = document.createElement("span");
                badge.className = "file-badge";
                badge.textContent = "Active";
                li.appendChild(badge);
            }

            // Delete button (admin only)
            var delBtn = document.createElement("button");
            delBtn.className = "file-delete-btn";
            delBtn.title = "Delete " + f.name;
            delBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>';
            delBtn.addEventListener("click", function (e) {
                e.stopPropagation();
                deleteFile(f.name);
            });
            li.appendChild(delBtn);

            fileList.appendChild(li);
        });
    }

    // ── Message rendering (shared) ─────────────────────────────────────────────

    function hideWelcomeCard() {
        if (welcomeCard && welcomeCard.parentNode) welcomeCard.remove();
    }

    var BOT_AVATAR_SVG =
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
        var av = document.createElement("div");
        av.className = "bot-avatar";
        av.innerHTML = BOT_AVATAR_SVG;
        return av;
    }

    function appendMessage(text, role, type) {
        type = type || "text";
        hideWelcomeCard();
        var div = document.createElement("div");
        div.className = "message " + role;
        if (type === "vacation_prompt") div.classList.add("vacation-prompt");
        if (role === "bot") div.appendChild(makeBotAvatar());
        var bubble = document.createElement("div");
        bubble.className = "bubble";
        if (type === "report") {
            var match = text.match(/report-file:\s*(.+)/i);
            var display = text.replace(/\nreport-file:[^\n]*/i, "").trim();
            bubble.innerHTML = renderMarkdown(display);
            if (match) {
                var fname = match[1].trim().split(/[/\\]/).pop() || "";
                var link = document.createElement("a");
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
        if (topbarSubtitle) topbarSubtitle.textContent = "Thinking…";
        var div = document.createElement("div");
        div.className = "message bot";
        div.id = "thinkingRow";
        div.appendChild(makeBotAvatar());
        var dots = document.createElement("div");
        dots.className = "thinking-dots";
        dots.innerHTML = "<span></span><span></span><span></span>";
        div.appendChild(dots);
        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        return div;
    }

    function renderMarkdown(text) {
        var html = text.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
        html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
        html = html.replace(/\n/g, "<br>");
        return html;
    }

    function setThinking(state) {
        isThinking = state;
        if (sendBtn) sendBtn.disabled = state;
        if (messageInput) messageInput.disabled = state;
        if (!state && topbarSubtitle) {
            topbarSubtitle.textContent = employees.length > 0
                ? "Admin — " + employees.length + " employee(s) loaded"
                : "Admin — No file loaded";
        }
    }

    function autoResizeTextarea() {
        if (!messageInput) return;
        messageInput.style.height = "auto";
        messageInput.style.height = Math.min(messageInput.scrollHeight, 160) + "px";
    }

    // ── Event listeners ────────────────────────────────────────────────────────

    if (sendBtn) sendBtn.addEventListener("click", sendMessage);

    if (messageInput) {
        messageInput.addEventListener("keydown", function (e) {
            if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
        });
        messageInput.addEventListener("input", autoResizeTextarea);
    }

    if (uploadZone && fileInput) {
        uploadZone.addEventListener("click", function () { fileInput.click(); });
        fileInput.addEventListener("change", function () {
            if (fileInput.files && fileInput.files[0]) uploadFile(fileInput.files[0]);
            fileInput.value = "";
        });
        uploadZone.addEventListener("dragover", function (e) {
            e.preventDefault(); uploadZone.classList.add("drag-over");
        });
        uploadZone.addEventListener("dragleave", function () {
            uploadZone.classList.remove("drag-over");
        });
        uploadZone.addEventListener("drop", function (e) {
            e.preventDefault();
            uploadZone.classList.remove("drag-over");
            var file = e.dataTransfer && e.dataTransfer.files[0];
            if (file) uploadFile(file);
        });
    }

    if (clearHistoryBtn) {
        clearHistoryBtn.addEventListener("click", async function () {
            await fetch("/api/clear-history", { method: "POST" });
            messagesEl.innerHTML = "";
        });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener("click", function () {
            fetchEmployees(); fetchFiles();
        });
    }

    if (newChatBtn) {
        newChatBtn.addEventListener("click", async function () {
            await fetch("/api/clear-history", { method: "POST" });
            messagesEl.innerHTML = "";
        });
    }

    if (hamburgerBtn && sidebar && overlay) {
        hamburgerBtn.addEventListener("click", function () {
            sidebar.classList.add("open");
            overlay.classList.add("visible");
        });
        overlay.addEventListener("click", function () {
            sidebar.classList.remove("open");
            overlay.classList.remove("visible");
        });
        var touchStartX = 0;
        sidebar.addEventListener("touchstart", function (e) {
            touchStartX = e.touches[0].clientX;
        }, { passive: true });
        sidebar.addEventListener("touchend", function (e) {
            if (e.changedTouches[0].clientX - touchStartX < -40) {
                sidebar.classList.remove("open");
                overlay.classList.remove("visible");
            }
        }, { passive: true });
    }

    // Collapsible sections
    document.querySelectorAll(".collapsible").forEach(function (header) {
        var target = header.dataset["target"];
        if (!target) return;
        var content = document.getElementById(target);
        if (!content) return;
        if (target === "fileListContent") {
            header.classList.add("open");   // keep files expanded for admin
        } else {
            header.classList.add("open");
        }
        header.addEventListener("click", function () {
            var isOpen = header.classList.toggle("open");
            content.classList.toggle("collapsed", !isOpen);
        });
    });

    if (statusDot) statusDot.classList.add("online");

})();
