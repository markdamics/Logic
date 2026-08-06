interface ComingSoonProps {
  title: string;
}

export function ComingSoon({ title }: ComingSoonProps) {
  return (
    <div className="coming-soon">
      <p>{title} isn't built yet — switch to Sources from the sidebar to manage log sources.</p>
    </div>
  );
}
