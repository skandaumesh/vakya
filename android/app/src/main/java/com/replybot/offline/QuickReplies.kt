package com.replybot.offline

import com.replybot.data.Suggestion

/**
 * Built-in replies that need no AI and no internet: the message's intent is detected
 * from keywords (English, Kanglish, Hinglish), and each intent has 3 replies taking
 * different actions, in a Kanglish, casual-English and polite version.
 */
object QuickReplies {

    enum class Register { KANGLISH, ENGLISH, POLITE }

    private class Option(val label: String, val kanglish: String, val english: String, val polite: String) {
        fun text(r: Register) = when (r) {
            Register.KANGLISH -> kanglish
            Register.ENGLISH -> english
            Register.POLITE -> polite
        }
    }

    private class Intent(val name: String, val pattern: Regex, val options: List<Option>)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // Most specific first: the first match wins.
    private val INTENTS = listOf(
        Intent("wished_me", re("""\b(happy (birthday|bday)|hbd|many happy returns)\b"""), listOf(
            Option("Thanks", "thank you maga 🙏❤️", "thank youu 🙏❤️", "Thank you so much!"),
            Option("Warm", "thanks da 🎉 party beku?", "thanks!! 🎉 means a lot", "Thank you, that means a lot."),
            Option("Short", "🙏🙏", "🙏🙏", "Thanks!"),
        )),
        Intent("congrats", re("""\b(got (the|a) job|passed|cleared|selected|placed|promoted|promotion|won|engaged)\b"""), listOf(
            Option("Congrats", "sakkath maga!! 🎉", "congrats!! 🎉", "Congratulations!"),
            Option("Celebrate", "congrats da 🔥 party beku", "that's awesome 🔥 party when?", "That's wonderful news, well done!"),
            Option("Ask", "super maga 😍 hege aytu?", "so happy for you 😍 tell me everything", "Great news! How did it go?"),
        )),
        Intent("good_morning", re("""\b(gm|good ?morn\w*|gud mrng)\b"""), listOf(
            Option("Greet", "gm maga ☀️", "gm ☀️", "Good morning!"),
            Option("Warm", "good morning da", "good morning!", "Good morning, have a great day."),
            Option("Ask", "gm! yen plan ivattu?", "morning! what's the plan today?", "Morning! How's your day looking?"),
        )),
        Intent("good_night", re("""\b(gn|good ?ni(ght|te)|gud n(i|y)te?)\b"""), listOf(
            Option("Greet", "gn maga 😴", "gn 😴", "Good night!"),
            Option("Warm", "good night da", "good night!", "Good night, take care."),
            Option("Later", "gn, naale sigona", "night, talk tomorrow", "Good night, talk tomorrow."),
        )),
        Intent("thanks", re("""\b(thanks|thank (you|u)|thx|thnx|ty|tq|dhanyavada|shukriya)\b"""), listOf(
            Option("Welcome", "anytime maga 🙌", "anytime 🙌", "You're welcome!"),
            Option("Chill", "paravagilla da", "no problem!", "Happy to help."),
            Option("Emoji", "😊👍", "😊", "Anytime."),
        )),
        Intent("sorry", re("""\b(sorry|sry|apologies|my bad|maaf)\b"""), listOf(
            Option("No worries", "paravagilla maga", "no worries!", "No worries at all."),
            Option("Reassure", "it's ok da, chill", "it's fine, chill", "That's alright, thanks for letting me know."),
            Option("Ok", "sari bidu 👍", "all good 👍", "No problem."),
        )),
        Intent("how_are_you", re("""\b(how are (you|u)|how r u|hru|hegidiya|hegiddiya|hegidira|kaise ho|kya haal|how's it going)\b"""), listOf(
            Option("Good", "chennagidini maga, neenu?", "all good, you?", "I'm doing well, thank you. How about you?"),
            Option("Ask back", "all good da, nin kathe?", "doing well! wbu?", "All good, thanks for asking!"),
            Option("Honest", "swalpa busy, but ok 😅", "bit busy but good 😅", "Doing well, a little busy."),
        )),
        Intent("where", re("""\b(where|wru|elli|yelli|kidhar|kahan|reached)\b"""), listOf(
            Option("On my way", "on the way maga 🏃", "on the way 🏃", "On my way."),
            Option("Soon", "5 min, bartini", "5 mins away", "I'll be there in 5 minutes."),
            Option("Late", "innu manele idini, bega bartini", "still at home, leaving soon", "Running a little late, will be there soon."),
        )),
        Intent("call_me", re("""\b(call me|call madu|call kar|ring me|phone madu|can we talk)\b"""), listOf(
            Option("Soon", "sari, 10 min alli call madtini", "sure, will call in 10", "Sure, I'll call you shortly."),
            Option("Later", "ivaga busy, amele call madla?", "busy rn, call later?", "I'm busy right now, can I call you later?"),
            Option("Now", "okay calling now 📞", "calling now 📞", "Calling you now."),
        )),
        // Clear invite words always count; plain topics (party, movie...) only when asked as a question.
        Intent("invitation", re("""\b(barthiya|bartiya|baro|chalein|chalo|let'?s go|hogona|join us|wanna come|you coming)\b|\b(party|movie|trip|plan|dinner|lunch|outing)\b[^?]*\?"""), listOf(
            Option("Accept", "bartini maga 🔥", "I'm in 🔥", "Sure, I'll be there."),
            Option("Maybe", "nodona, confirm madtini", "maybe, will confirm", "Let me check and confirm."),
            Option("Decline", "sorry maga, aagalla this time", "can't make it this time 😕", "Sorry, I won't be able to make it."),
        )),
        Intent("urgent", re("""\b(urgent|asap|emergency|immediately|bega|jaldi)\b"""), listOf(
            Option("On it", "ivaga nodtini", "checking now", "Checking right away."),
            Option("Call", "call madtini ivaga", "calling you now", "Calling you now."),
            Option("Soon", "swalpa busy, 10 min", "give me 10 min", "I'll look into it in 10 minutes."),
        )),
        Intent("request", re("""\b(can (you|u)|could (you|u)|pls|plz|please|send|madu|kalsu|bhej|kar do)\b"""), listOf(
            Option("Accept", "sari, madtini 👍", "sure, will do 👍", "Sure, I'll take care of it."),
            Option("Delay", "swalpa time beku, amele madtini", "give me a bit, will do it soon", "I'll need a little time, will update you soon."),
            Option("Clarify", "yavdu exactly?", "which one exactly?", "Could you share a few more details?"),
        )),
        Intent("laugh", re("""(😂|🤣|💀|\[sticker]|\[gif]|\b(ha(ha)+|lol|lmao|hehe+)\b)"""), listOf(
            Option("Laugh", "😂😂", "😂😂", "😄"),
            Option("React", "hahaha sakkath", "lmao", "Haha!"),
            Option("Dead", "💀💀", "💀", "That's funny!"),
        )),
        Intent("photo", re("""\[(photo|video)]"""), listOf(
            Option("Love it", "sakkath aagide 🔥", "looks great 🔥", "Looks great!"),
            Option("Ask", "yelli idu?", "where is this?", "Where was this taken?"),
            Option("React", "nice maga 😍", "nice! 😍", "Nice picture!"),
        )),
        Intent("ok", re("""^\s*(ok+|okay|k+|done|sari|fine|cool|hm+|alright)\W*$"""), listOf(
            Option("Ok", "👍", "👍", "Okay, thanks."),
            Option("Sure", "sari", "cool", "Noted."),
            Option("Done", "done maga", "done", "Sounds good."),
        )),
        Intent("question", re("""\?\s*$"""), listOf(
            Option("Yes", "houdu", "yes", "Yes."),
            Option("No", "illa maga", "nope", "No, unfortunately."),
            Option("Not sure", "gottilla, check madi heltini", "not sure, will check", "Not sure, I'll check and get back to you."),
        )),
    )

    private val FALLBACK = listOf(
        Option("Ok", "haan maga", "okay 👍", "Okay, noted."),
        Option("Got it", "sari 👍", "got it", "Got it, thanks."),
        Option("Ask", "yen aytu?", "what happened?", "Could you tell me more?"),
    )

    private val KANGLISH_WORDS = setOf(
        "maga", "macha", "guru", "illa", "beda", "gottilla", "aytu", "aaytu", "madtini", "madthini",
        "bartini", "barthini", "barthiya", "bartiya", "swalpa", "yen", "yenu", "houdu", "nange", "ninge",
        "sari", "ide", "idhe", "hogona", "banni", "beku", "naale", "ivattu", "yaake", "yelli", "elli", "hege",
        "chennagide", "sakkath", "bidu", "oota", "aythu", "madbeku", "nodona", "gotthu", "idini", "idiya", "da", "le", "mari",
    )

    /** The detected intent's name, or null when nothing matched (useful for tests and logs). */
    fun intentOf(message: String): String? = INTENTS.firstOrNull { it.pattern.containsMatchIn(message) }?.name

    /**
     * Kanglish when they (or I, in [myRecent]) write Kanglish, polite for non-friends,
     * casual English otherwise.
     */
    fun registerFor(message: String, myRecent: List<String>, formal: Boolean): Register {
        if (formal) return Register.POLITE
        val words = (listOf(message) + myRecent).flatMap { Regex("[a-z]+").findAll(it.lowercase()).map { m -> m.value }.toList() }
        return if (words.any { it in KANGLISH_WORDS }) Register.KANGLISH else Register.ENGLISH
    }

    fun suggest(message: String, register: Register): List<Suggestion> {
        val options = INTENTS.firstOrNull { it.pattern.containsMatchIn(message) }?.options ?: FALLBACK
        return options.map { Suggestion(it.label, it.text(register)) }
    }
}
