package com.truyencloud.shared.repo

/**
 * Từ điển nhỏ đi kèm [EnglishLemmatizer]: dạng bất quy tắc và các ngoại lệ mà luật
 * hậu tố không tự đoán được. Tách riêng để luật hình thái dễ đọc.
 */
internal object EnglishLemmaLexicon {

    private fun forms(base: String, vararg inflected: String): List<Pair<String, String>> =
        inflected.map { it to base }

    /** Dạng bất quy tắc -> nguyên thể. Tra TRƯỚC mọi luật (kể cả từ <= 3 chữ như "ran"). */
    val IRREGULAR: Map<String, String> = buildList {
        addAll(forms("be", "is", "are", "am", "was", "were", "been", "being"))
        addAll(forms("have", "has", "had", "having"))
        addAll(forms("do", "does", "did", "done", "doing"))
        addAll(forms("go", "goes", "went", "gone", "going"))
        addAll(forms("run", "ran", "runs", "running"))
        addAll(forms("say", "says", "said", "saying"))
        addAll(forms("make", "made", "makes", "making"))
        addAll(forms("take", "took", "taken", "taking"))
        addAll(forms("come", "came", "comes", "coming"))
        addAll(forms("see", "saw", "seen", "seeing", "sees"))
        addAll(forms("know", "knew", "known", "knowing"))
        addAll(forms("think", "thought"))
        addAll(forms("feel", "felt"))
        addAll(forms("leave", "left"))
        addAll(forms("hold", "held"))
        addAll(forms("bring", "brought"))
        addAll(forms("catch", "caught"))
        addAll(forms("fall", "fell", "fallen"))
        addAll(forms("give", "gave", "given", "giving", "gives"))
        addAll(forms("find", "found"))
        addAll(forms("get", "got", "gotten"))
        addAll(forms("tell", "told"))
        addAll(forms("become", "became", "becoming"))
        addAll(forms("begin", "began", "begun"))
        addAll(forms("break", "broke", "broken"))
        addAll(forms("buy", "bought"))
        addAll(forms("build", "built"))
        addAll(forms("choose", "chose", "chosen"))
        addAll(forms("draw", "drew", "drawn"))
        addAll(forms("drink", "drank", "drunk"))
        addAll(forms("drive", "drove", "driven"))
        addAll(forms("eat", "ate", "eaten"))
        addAll(forms("fly", "flew", "flown"))
        addAll(forms("forget", "forgot", "forgotten"))
        addAll(forms("forgive", "forgave", "forgiven"))
        addAll(forms("freeze", "froze", "frozen"))
        addAll(forms("grow", "grew", "grown"))
        addAll(forms("hide", "hid", "hidden"))
        addAll(forms("keep", "kept"))
        addAll(forms("kneel", "knelt"))
        addAll(forms("lead", "led"))
        addAll(forms("lend", "lent"))
        addAll(forms("lose", "lost"))
        addAll(forms("mean", "meant"))
        addAll(forms("meet", "met"))
        addAll(forms("pay", "paid"))
        addAll(forms("ride", "rode", "ridden"))
        addAll(forms("ring", "rang", "rung"))
        addAll(forms("rise", "risen"))
        addAll(forms("sing", "sang", "sung"))
        addAll(forms("sink", "sank", "sunk"))
        addAll(forms("sit", "sat"))
        addAll(forms("sleep", "slept"))
        addAll(forms("slide", "slid"))
        addAll(forms("sell", "sold"))
        addAll(forms("send", "sent"))
        addAll(forms("shake", "shook", "shaken"))
        addAll(forms("shine", "shone"))
        addAll(forms("shoot", "shot"))
        addAll(forms("speak", "spoke", "spoken"))
        addAll(forms("speed", "sped"))
        addAll(forms("spend", "spent"))
        addAll(forms("spin", "spun"))
        addAll(forms("stand", "stood"))
        addAll(forms("steal", "stole", "stolen"))
        addAll(forms("stick", "stuck"))
        addAll(forms("strike", "struck"))
        addAll(forms("swear", "swore", "sworn"))
        addAll(forms("swim", "swam", "swum"))
        addAll(forms("sweep", "swept"))
        addAll(forms("swing", "swung"))
        addAll(forms("teach", "taught"))
        addAll(forms("tear", "tore", "torn"))
        addAll(forms("throw", "threw", "thrown"))
        addAll(forms("understand", "understood"))
        addAll(forms("wake", "woke", "woken"))
        addAll(forms("wear", "wore", "worn"))
        addAll(forms("win", "won"))
        addAll(forms("write", "wrote", "written"))
        addAll(forms("bite", "bitten"))
        addAll(forms("blow", "blew", "blown"))
        addAll(forms("bend", "bent"))
        addAll(forms("breed", "bred"))
        addAll(forms("deal", "dealt"))
        addAll(forms("dig", "dug"))
        addAll(forms("feed", "fed"))
        addAll(forms("flee", "fled"))
        addAll(forms("fight", "fought"))
        addAll(forms("hang", "hung"))
        addAll(forms("hear", "heard"))
        addAll(forms("seek", "sought"))
        addAll(forms("weep", "wept"))
        addAll(forms("cling", "clung"))
        addAll(forms("creep", "crept"))
        addAll(forms("arise", "arose", "arisen"))
        addAll(forms("awake", "awoke", "awoken"))
        addAll(forms("bleed", "bled"))
        addAll(forms("overcome", "overcame"))
        addAll(forms("withdraw", "withdrew", "withdrawn"))
        addAll(forms("die", "died", "dies", "dying"))
        addAll(forms("lie", "lied", "lies", "lying"))
        addAll(forms("tie", "tied", "ties", "tying"))
        addAll(forms("dye", "dyed", "dyes", "dyeing"))
        addAll(forms("eye", "eyed", "eyes", "eyeing", "eying"))
        addAll(forms("owe", "owed", "owes", "owing"))
        addAll(forms("use", "used"))
        addAll(forms("age", "aged"))
        addAll(forms("vie", "vied", "vies", "vying"))
        addAll(forms("ache", "ached", "aches", "aching"))
        addAll(forms("quiz", "quizzed", "quizzes", "quizzing"))
        // Danh từ số nhiều bất quy tắc
        addAll(forms("child", "children"))
        addAll(forms("man", "men"))
        addAll(forms("woman", "women"))
        addAll(forms("foot", "feet"))
        addAll(forms("tooth", "teeth"))
        addAll(forms("goose", "geese"))
        addAll(forms("mouse", "mice"))
        addAll(forms("person", "people"))
        addAll(forms("wolf", "wolves"))
        addAll(forms("knife", "knives"))
        addAll(forms("wife", "wives"))
                addAll(forms("half", "halves"))
        addAll(forms("self", "selves"))
        addAll(forms("shelf", "shelves"))
        addAll(forms("thief", "thieves"))
        addAll(forms("loaf", "loaves"))
        addAll(forms("calf", "calves"))
        addAll(forms("elf", "elves"))
        addAll(forms("scarf", "scarves"))
        addAll(forms("hoof", "hooves"))
        addAll(forms("gas", "gases"))
        addAll(forms("lens", "lenses"))
        addAll(forms("atlas", "atlases"))
        addAll(forms("canvas", "canvases"))
        addAll(forms("alias", "aliases"))
        addAll(forms("bias", "biases"))
        addAll(forms("crisis", "crises"))
        addAll(forms("thesis", "theses"))
        addAll(forms("analysis", "analyses"))
        addAll(forms("diagnosis", "diagnoses"))
        addAll(forms("hypothesis", "hypotheses"))
        addAll(forms("oasis", "oases"))
        addAll(forms("axe", "axes"))
        addAll(forms("criterion", "criteria"))
        addAll(forms("phenomenon", "phenomena"))
        // Tính từ so sánh bất quy tắc
        addAll(forms("good", "better", "best"))
        addAll(forms("bad", "worse", "worst"))
    }.toMap()

    /** Trông như đã chia nhưng thực ra là nguyên thể — giữ nguyên. */
    val BASE_FORMS: Set<String> = setOf(
        // -s
        "news", "series", "species", "always", "perhaps", "sometimes", "besides", "towards", "nowadays",
        "sideways", "anyways", "bias", "atlas", "canvas", "alias", "lens", "gas", "christmas", "texas",
        "afterwards", "backwards", "upwards", "downwards", "forwards", "whereas", "nevertheless",
        "chaos", "cosmos", "ethos", "pathos", "kudos", "christmas", "pancreas", "overseas",
        "physics", "mathematics", "politics", "economics", "ethics", "athletics", "gymnastics",
        "linguistics", "electronics", "genetics", "statistics", "acoustics", "aesthetics",
        "clothes", "trousers", "scissors", "whereabouts", "headquarters", "crossroads",
        "outskirts", "surroundings", "belongings", "congratulations",
        // đại từ sở hữu, trạng từ nơi chốn và tên bệnh: không phải số nhiều ("yours" khác "your", "measles" không phải "measle")
        "yours", "ours", "hers", "theirs", "upstairs", "downstairs", "indoors", "outdoors",
        "diabetes", "measles", "rabies",
        // -ing
        "during", "evening", "morning", "nothing", "something", "anything", "everything",
        "ceiling", "wedding", "pudding", "sibling", "darling", "duckling", "awning", "herring",
        "lightning", "earring", "offspring", "inkling", "viking", "building", "clothing",
        "interesting", "amazing", "boring", "charming", "stunning", "exciting", "surprising",
        "fascinating", "overwhelming", "breathtaking", "outstanding", "astonishing", "annoying",
        "embarrassing", "frightening", "terrifying", "disappointing", "satisfying", "convincing",
        "compelling", "depressing", "alarming", "thrilling", "heartwarming", "heartbreaking",
        "captivating", "intriguing", "bewildering", "dazzling", "shocking", "promising",
        "striking", "willing", "cunning", "daring", "sterling", "shilling", "sapling", "starling",
        "plaything", "icing", "according",
        // -ed
        "hundred", "sacred", "naked", "wicked", "wretched", "beloved", "kindred", "rugged",
        "ragged", "crooked", "jagged", "dogged", "hatred", "shed", "sled"
    )

    /** Động từ tận cùng -ee: "agreed" -> "agree" (khác "need", "speed" vốn là nguyên thể). */
    val EE_VERBS: Set<String> = setOf(
        "agree", "free", "decree", "guarantee", "referee", "disagree", "knee", "emcee"
    )

    /** "uses" -> "use" chứ không phải "us"; "buses" -> "bus". */
    val USE_WORDS: Set<String> = setOf(
        "use", "excuse", "refuse", "abuse", "fuse", "muse", "accuse", "confuse", "amuse",
        "ruse", "cause", "pause", "clause", "house", "blouse", "spouse", "peruse", "diffuse",
        "infuse", "recluse", "misuse", "reuse", "overuse", "disuse", "warehouse", "greenhouse"
    )

    /** "headaches" -> "headache" chứ không phải "headach". */
    val CHE_WORDS: Set<String> = setOf(
        "ache", "headache", "toothache", "stomachache", "heartache", "cache", "niche", "cliche",
        "moustache", "mustache", "avalanche", "psyche", "creche", "quiche", "douche"
    )

    /** "shoes" -> "shoe" chứ không phải "sho"; "heroes" -> "hero". */
    val OE_WORDS: Set<String> = setOf(
        "shoe", "toe", "foe", "hoe", "canoe", "oboe", "tiptoe", "woe", "horseshoe", "floe",
        "sloe", "roe", "doe", "mistletoe", "throe", "aloe"
    )

    /** "movies" -> "movie" chứ không phải "movy". */
    val IE_NOUNS: Set<String> = setOf(
        "movie", "cookie", "zombie", "calorie", "prairie", "brownie", "hippie", "rookie",
        "selfie", "goodie", "auntie", "hoodie", "genie", "pixie", "magpie", "necktie", "sortie",
        "freebie", "newbie", "veggie", "birdie", "cutie", "sweetie", "collie", "lingerie", "eerie"
    )

    /** "changed" -> "change", nhưng "belonged" -> "belong". */
    val NG_E_WORDS: Set<String> = setOf(
        "change", "exchange", "arrange", "rearrange", "range", "challenge", "plunge", "lunge",
        "cringe", "fringe", "hinge", "binge", "sponge", "avenge", "revenge", "estrange",
        "derange", "scrounge", "lounge", "impinge", "infringe", "whinge", "twinge", "syringe", "tinge"
    )

    /** "controlled" -> "control" (khác "called" -> "call"). */
    val LL_UNDOUBLE: Set<String> = setOf(
        "control", "patrol", "compel", "expel", "propel", "repel", "excel", "label", "travel",
        "cancel", "model", "quarrel", "fuel", "level", "signal", "marvel", "dial", "total",
        "channel", "counsel", "rebel", "enrol", "distil", "fulfil", "instil", "annul", "equal"
    )

    /**
     * Gốc (đã bỏ -ed/-ing) cần thêm "e" mà luật chung không đoán được:
     * từ nhiều âm tiết, cặp nguyên âm, hoặc cụm phụ âm.
     */
    val SILENT_E_STEMS: Set<String> = setOf(
        "creat", "recreat", "guid", "persuad", "dissuad", "complet", "compet", "delet", "deplet",
        "excret", "promot", "devot", "denot", "demot", "emot", "connot", "invit", "excit", "unit",
        "reunit", "recit", "ignit", "incit", "expedit", "welcom", "becom", "overcom", "inflam",
        "postpon", "condon", "aton", "inton", "dethron", "enthron", "compil", "reconcil", "exil",
        "defil", "stockpil", "beguil", "schedul", "ridicul", "overrul", "consol", "cajol", "parol",
        "exhal", "inhal", "impal", "interfer", "persever", "adher", "coher", "rever", "ignor",
        "explor", "restor", "ador", "implor", "deplor", "elop", "tast", "wast", "past", "hast",
        "bast", "breath", "sooth", "loath", "cloth", "seeth", "bath", "teeth", "wreath",
        "quot", "rhym", "requir", "acquir", "inquir", "enquir"
    )

    /** Gốc KHÔNG thêm "e" dù có dạng phụ âm-nguyên âm-phụ âm. */
    val NO_E_STEMS: Set<String> = setOf(
        "murmur", "debut", "combat", "catalog", "dialog", "focus", "bus", "nonplus", "canvas",
        "bias", "gas", "input", "output", "sugar", "collar", "cellar", "pillar", "hangar",
        "develop", "worship", "gossip", "gallop", "envelop", "blossom", "bottom", "ransom"
    )

    /** Gốc vốn đã kết thúc bằng phụ âm kép — KHÔNG được rút còn một chữ: "added" -> "add". */
    val NATURAL_DOUBLES: Set<String> = setOf(
        "add", "odd", "egg", "inn", "err", "purr", "burr", "ebb", "butt", "boycott", "whirr"
    )
}
