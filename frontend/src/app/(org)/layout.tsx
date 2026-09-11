import { OrgShell } from "@/components/org/Shell";

export default function OrgLayout({ children }: { children: React.ReactNode }) {
  return <OrgShell>{children}</OrgShell>;
}
