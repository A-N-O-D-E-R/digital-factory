import { expect, type Page, test } from "@playwright/test";

/** Mock login (dev/run-local.sh): Spring's login form, any password. */
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login/); // no session: the app sends the user to log in
  await page.getByLabel("Username").fill(user);
  await page.getByLabel("Password").fill("x");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Tableau" })).toBeVisible();
}

test("an admin signs in, moves around and sees the factory's state", async ({ page }) => {
  await signIn(page, "ada");
  await expect(page.getByRole("button", { name: "Nouveau work order" })).toBeVisible();

  await page.keyboard.press("Control+k");
  await page.getByPlaceholder("Aller à…").fill("Intégr");
  await page.keyboard.press("Enter");
  await expect(page.getByText("Non configurée").first()).toBeVisible(); // nothing set up locally

  await page.goto("/workflows/default");
  await expect(page.getByText(/action watch-pr/)).toBeVisible();
  await page.getByRole("button", { name: "Valider" }).click();
  await expect(page.getByText("Workflow valide.")).toBeVisible();
});

test("a viewer sees the factory but cannot change it", async ({ page }) => {
  await signIn(page, "vic");
  await expect(page.getByRole("button", { name: "Nouveau work order" })).toHaveCount(0);
  await page.goto("/admin/workers");
  await expect(page.getByText("Réservé aux administrateurs.")).toBeVisible();
});
