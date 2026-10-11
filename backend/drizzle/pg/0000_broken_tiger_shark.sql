CREATE TABLE "audit_logs" (
	"id" serial PRIMARY KEY NOT NULL,
	"actor_user_id" integer,
	"action" text NOT NULL,
	"resource_type" text NOT NULL,
	"resource_id" integer,
	"metadata" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "claim_receipts" (
	"id" serial PRIMARY KEY NOT NULL,
	"claim_id" integer NOT NULL,
	"receipt_id" integer NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "claims" (
	"id" serial PRIMARY KEY NOT NULL,
	"user_id" integer NOT NULL,
	"title" text NOT NULL,
	"start_date" timestamp with time zone,
	"end_date" timestamp with time zone,
	"status" text DEFAULT 'DRAFT' NOT NULL,
	"total_amount" double precision DEFAULT 0 NOT NULL,
	"total_sgd_equivalent" double precision DEFAULT 0 NOT NULL,
	"submitted_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "exchange_rates" (
	"id" serial PRIMARY KEY NOT NULL,
	"source_currency" text NOT NULL,
	"target_currency" text DEFAULT 'SGD' NOT NULL,
	"rate_date" timestamp with time zone NOT NULL,
	"rate" double precision NOT NULL,
	"provider" text DEFAULT 'frankfurter' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "receipt_categories" (
	"id" serial PRIMARY KEY NOT NULL,
	"name" text NOT NULL,
	"active" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "receipt_categories_name_unique" UNIQUE("name")
);
--> statement-breakpoint
CREATE TABLE "receipts" (
	"id" serial PRIMARY KEY NOT NULL,
	"user_id" integer NOT NULL,
	"client_receipt_id" text,
	"category_id" integer NOT NULL,
	"receipt_date" timestamp with time zone NOT NULL,
	"amount" double precision NOT NULL,
	"currency" text DEFAULT 'SGD' NOT NULL,
	"exchange_rate" double precision DEFAULT 1 NOT NULL,
	"sgd_equivalent" double precision NOT NULL,
	"description" text,
	"note" text,
	"storage_key" text,
	"original_file_name" text,
	"mime_type" text,
	"status" text DEFAULT 'UNCLAIMED' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "report_files" (
	"id" serial PRIMARY KEY NOT NULL,
	"report_id" integer NOT NULL,
	"file_type" text NOT NULL,
	"storage_key" text NOT NULL,
	"file_name" text NOT NULL,
	"mime_type" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "reports" (
	"id" serial PRIMARY KEY NOT NULL,
	"user_id" integer NOT NULL,
	"claim_id" integer NOT NULL,
	"status" text DEFAULT 'GENERATING' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone,
	"error_message" text
);
--> statement-breakpoint
CREATE TABLE "users" (
	"id" serial PRIMARY KEY NOT NULL,
	"username" text NOT NULL,
	"display_name" text NOT NULL,
	"password_hash" text NOT NULL,
	"role" text DEFAULT 'USER' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "users_username_unique" UNIQUE("username")
);
--> statement-breakpoint
ALTER TABLE "audit_logs" ADD CONSTRAINT "audit_logs_actor_user_id_users_id_fk" FOREIGN KEY ("actor_user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "claim_receipts" ADD CONSTRAINT "claim_receipts_claim_id_claims_id_fk" FOREIGN KEY ("claim_id") REFERENCES "public"."claims"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "claim_receipts" ADD CONSTRAINT "claim_receipts_receipt_id_receipts_id_fk" FOREIGN KEY ("receipt_id") REFERENCES "public"."receipts"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "claims" ADD CONSTRAINT "claims_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "receipts" ADD CONSTRAINT "receipts_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "receipts" ADD CONSTRAINT "receipts_category_id_receipt_categories_id_fk" FOREIGN KEY ("category_id") REFERENCES "public"."receipt_categories"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "report_files" ADD CONSTRAINT "report_files_report_id_reports_id_fk" FOREIGN KEY ("report_id") REFERENCES "public"."reports"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "reports" ADD CONSTRAINT "reports_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "reports" ADD CONSTRAINT "reports_claim_id_claims_id_fk" FOREIGN KEY ("claim_id") REFERENCES "public"."claims"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "claim_receipt_unique_idx" ON "claim_receipts" USING btree ("claim_id","receipt_id");--> statement-breakpoint
CREATE INDEX "claim_receipts_claim_idx" ON "claim_receipts" USING btree ("claim_id");--> statement-breakpoint
CREATE INDEX "claims_user_status_idx" ON "claims" USING btree ("user_id","status");--> statement-breakpoint
CREATE UNIQUE INDEX "exchange_rate_unique_idx" ON "exchange_rates" USING btree ("source_currency","target_currency","rate_date");--> statement-breakpoint
CREATE UNIQUE INDEX "receipts_client_receipt_unique_idx" ON "receipts" USING btree ("user_id","client_receipt_id");--> statement-breakpoint
CREATE INDEX "receipts_user_date_idx" ON "receipts" USING btree ("user_id","receipt_date");--> statement-breakpoint
CREATE INDEX "receipts_category_idx" ON "receipts" USING btree ("category_id");