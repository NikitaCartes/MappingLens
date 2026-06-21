interface Props {
  value: string;
  onChange: (v: string) => void;
  count?: number;
}

export function SearchBar({ value, onChange, count }: Props) {
  return (
    <div className="searchbar">
      <input
        type="text"
        className="search-input"
        placeholder="Search mappings…  e.g. BossBar, setProgress, class_1259, Block#tick"
        value={value}
        autoFocus
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
      />
      {count !== undefined && (
        <span className="search-count">
          {count} result{count === 1 ? "" : "s"}
        </span>
      )}
    </div>
  );
}
