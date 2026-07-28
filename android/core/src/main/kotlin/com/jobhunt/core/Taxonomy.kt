package com.jobhunt.core

/**
 * A compact skills taxonomy used to recognize skills in free resume text.
 *
 * Intentionally broad (tech, education, healthcare, trades, business) so the
 * app works for anyone. Matching is case-insensitive on word boundaries;
 * multi-word entries match as phrases. Users extend this implicitly:
 * anything under a resume's own "Skills" section is captured verbatim too.
 */
object Taxonomy {

    val SKILLS: List<String> = listOf(
        // --- Software / IT ---
        "python", "java", "javascript", "typescript", "c++", "c#", "go", "rust",
        "ruby", "php", "sql", "nosql", "html", "css", "react", "angular", "vue",
        "node.js", "django", "flask", "fastapi", "spring", ".net", "aws", "azure",
        "google cloud", "gcp", "docker", "kubernetes", "terraform", "ansible",
        "linux", "windows server", "git", "ci/cd", "devops", "machine learning",
        "deep learning", "data analysis", "data science", "data engineering",
        "etl", "tableau", "power bi", "excel", "vba", "salesforce", "sap",
        "networking", "cybersecurity", "penetration testing", "incident response",
        "help desk", "technical support", "system administration",
        "database administration", "web development", "mobile development",
        "api design", "rest api", "graphql", "microservices", "agile", "scrum",
        "kanban", "jira", "confluence", "qa", "test automation", "selenium",
        "kotlin", "swift", "android development", "ios development",
        // --- Education / instructional ---
        "instructional design", "curriculum development", "curriculum design",
        "lesson planning", "classroom management", "e-learning", "lms",
        "learning management systems", "canvas", "blackboard", "moodle",
        "google classroom", "universal design for learning", "udl",
        "differentiated instruction", "assessment design", "student engagement",
        "special education", "iep", "early childhood education", "adult learning",
        "andragogy", "pedagogy", "educational technology", "edtech",
        "online learning", "distance learning", "blended learning",
        "faculty development", "academic advising", "accreditation",
        "program evaluation", "learning outcomes", "scorm", "articulate storyline",
        "adobe captivate", "camtasia", "training facilitation",
        "professional development", "workshop facilitation", "mentoring",
        "tutoring", "literacy instruction", "stem education",
        // --- Accessibility / compliance ---
        "accessibility", "wcag", "ada compliance", "section 508", "aria",
        "screen readers", "assistive technology", "usability testing",
        "title ix", "ferpa", "hipaa", "osha", "gdpr",
        // --- Business / management ---
        "project management", "program management", "product management",
        "change management", "strategic planning", "budget management",
        "budgeting", "forecasting", "financial analysis", "accounting",
        "bookkeeping", "quickbooks", "payroll", "procurement", "contract management",
        "vendor management", "stakeholder management", "risk management",
        "operations management", "process improvement", "lean", "six sigma",
        "business analysis", "business development", "grant writing",
        "fundraising", "policy development", "compliance", "auditing",
        "human resources", "recruiting", "talent acquisition", "onboarding",
        "performance management", "employee relations", "training and development",
        "leadership", "team leadership", "team building", "supervision",
        "coaching", "conflict resolution", "negotiation", "public speaking",
        "presentation skills", "report writing", "technical writing",
        "communication", "customer service", "client relations", "crm",
        // --- Marketing / creative ---
        "digital marketing", "content marketing", "social media marketing",
        "social media management", "seo", "sem", "email marketing",
        "google analytics", "google ads", "copywriting", "content creation",
        "graphic design", "adobe photoshop", "adobe illustrator", "indesign",
        "video editing", "adobe premiere", "after effects", "photography",
        "ui design", "ux design", "ux research", "figma", "wireframing",
        "brand management", "market research", "public relations",
        // --- Healthcare ---
        "patient care", "patient education", "clinical research", "phlebotomy",
        "medication administration", "vital signs", "electronic health records",
        "ehr", "epic", "cerner", "medical terminology", "medical coding",
        "icd-10", "cpt coding", "care coordination", "case management",
        "behavioral health", "mental health counseling", "crisis intervention",
        "first aid", "cpr", "bls", "acls", "infection control", "telehealth",
        // --- Trades / logistics / service ---
        "carpentry", "plumbing", "electrical", "hvac", "welding", "machining",
        "cnc", "blueprint reading", "forklift operation", "osha 10", "osha 30",
        "inventory management", "warehouse operations", "supply chain",
        "logistics", "fleet management", "cdl", "equipment maintenance",
        "preventive maintenance", "quality control", "food safety", "servsafe",
        "culinary arts", "barista", "bartending", "retail sales", "cash handling",
        "point of sale", "merchandising", "landscaping", "horticulture",
        // --- Languages ---
        "spanish", "french", "german", "mandarin", "american sign language", "asl",
    )

    val CERTIFICATIONS: List<String> = listOf(
        "pmp", "capm", "csm", "certified scrummaster", "safe agilist",
        "comptia a+", "comptia network+", "comptia security+", "ccna", "ccnp",
        "aws certified", "azure certified", "google certified",
        "cissp", "ceh", "cisa", "cism",
        "cpa", "cfa", "shrm-cp", "shrm-scp", "phr", "sphr",
        "six sigma green belt", "six sigma black belt", "lean six sigma",
        "itil", "prince2",
        "cpr certified", "first aid certified", "bls certified", "acls certified",
        "rn license", "lpn license", "cna", "emt", "paramedic",
        "teaching certificate", "teaching certification", "teaching license",
        "cda", "child development associate",
        "cpacc", "was certification", "section 508 certified",
        "servsafe certified", "osha 10 certified", "osha 30 certified",
        "cdl class a", "cdl class b", "notary public",
    )
}
